package expo.modules.updates

import android.content.Context
import android.os.AsyncTask
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import com.facebook.react.ReactApplication
import com.facebook.react.ReactInstanceManager
import com.facebook.react.ReactNativeHost
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.JSBundleLoader
import com.facebook.react.bridge.WritableMap
import expo.modules.updates.db.BuildData
import expo.modules.updates.db.DatabaseHolder
import expo.modules.updates.db.Reaper
import expo.modules.updates.db.UpdatesDatabase
import expo.modules.updates.db.entity.AssetEntity
import expo.modules.updates.db.entity.UpdateEntity
import expo.modules.updates.errorrecovery.ErrorRecovery
import expo.modules.updates.errorrecovery.ErrorRecoveryDelegate
import expo.modules.updates.launcher.DatabaseLauncher
import expo.modules.updates.launcher.Launcher
import expo.modules.updates.launcher.Launcher.LauncherCallback
import expo.modules.updates.launcher.NoDatabaseLauncher
import expo.modules.updates.loader.*
import expo.modules.updates.loader.LoaderTask.RemoteUpdateStatus
import expo.modules.updates.loader.LoaderTask.LoaderTaskCallback
import expo.modules.updates.logging.UpdatesErrorCode
import expo.modules.updates.logging.UpdatesLogReader
import expo.modules.updates.logging.UpdatesLogger
import expo.modules.updates.manifest.EmbeddedManifest
import expo.modules.updates.manifest.ManifestMetadata
import expo.modules.updates.manifest.UpdateManifest
import expo.modules.updates.selectionpolicy.LauncherSelectionPolicySingleUpdate
import expo.modules.updates.selectionpolicy.ReaperSelectionPolicyDevelopmentClient
import expo.modules.updates.selectionpolicy.SelectionPolicy
import expo.modules.updates.selectionpolicy.SelectionPolicyFactory
import expo.modules.updates.statemachine.UpdatesStateChangeEventSender
import expo.modules.updates.statemachine.UpdatesStateContext
import expo.modules.updates.statemachine.UpdatesStateEvent
import expo.modules.updates.statemachine.UpdatesStateEventType
import expo.modules.updates.statemachine.UpdatesStateMachine
import expo.modules.updates.statemachine.UpdatesStateValue
import expo.modules.updatesinterface.UpdatesInterface
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.util.Date
import java.util.HashMap

/**
 * Main entry point to expo-updates in normal release builds (development clients, including Expo
 * Go, use a different entry point). Singleton that keeps track of updates state, holds references
 * to instances of other updates classes, and is the central hub for all updates-related tasks.
 *
 * The `start` method in this class should be invoked early in the application lifecycle, via
 * [UpdatesPackage]. It delegates to an instance of [LoaderTask] to start the process of loading and
 * launching an update, then responds appropriately depending on the callbacks that are invoked.
 *
 * This class also provides getter methods to access information about the updates state, which are
 * used by the exported [UpdatesModule]. Such information includes
 * references to: the database, the [UpdatesConfiguration] object, the path on disk to the updates
 * directory, any currently active [LoaderTask], the current [SelectionPolicy], the error recovery
 * handler, and the current launched update. This class is intended to be the source of truth for
 * these objects, so other classes shouldn't retain any of them indefinitely.
 *
 * This class also optionally holds a reference to the app's [ReactNativeHost], which allows
 * expo-updates to reload JS and send events through the bridge.
 */
class UpdatesController private constructor(
  context: Context,
  updatesConfiguration: UpdatesConfiguration
) : UpdatesStateChangeEventSender {
  var updatesConfiguration = updatesConfiguration
    private set

  private var reactNativeHost: WeakReference<ReactNativeHost>? = if (context is ReactApplication) {
    WeakReference(context.reactNativeHost)
  } else {
    null
  }

  var updatesDirectory: File? = null
  private var updatesDirectoryException: Exception? = null
  private val stateMachine = UpdatesStateMachine(context, this)

  private var launcher: Launcher? = null
  private val databaseHolder = DatabaseHolder(UpdatesDatabase.getInstance(context))

  // TODO: move away from DatabaseHolder pattern to Handler thread
  private val databaseHandlerThread = HandlerThread("expo-updates-database")
  private lateinit var databaseHandler: Handler
  private fun initializeDatabaseHandler() {
    if (!::databaseHandler.isInitialized) {
      databaseHandlerThread.start()
      databaseHandler = Handler(databaseHandlerThread.looper)
    }
  }

  private fun purgeUpdatesLogsOlderThanOneDay(context: Context) {
    UpdatesLogReader(context).purgeLogEntries {
      if (it != null) {
        Log.e(TAG, "UpdatesLogReader: error in purgeLogEntries", it)
      }
    }
  }

  private val logger = UpdatesLogger(context)
  private var isStarted = false
  private var loaderTask: LoaderTask? = null
  private var remoteLoadStatus = ErrorRecoveryDelegate.RemoteLoadStatus.IDLE

  private var mSelectionPolicy: SelectionPolicy? = null
  private var defaultSelectionPolicy: SelectionPolicy = SelectionPolicyFactory.createFilterAwarePolicy(
    UpdatesUtils.getRuntimeVersion(updatesConfiguration)
  )
  private val fileDownloader: FileDownloader = FileDownloader(context)
  private val errorRecovery: ErrorRecovery = ErrorRecovery(context)

  private fun setRemoteLoadStatus(status: ErrorRecoveryDelegate.RemoteLoadStatus) {
    remoteLoadStatus = status
    errorRecovery.notifyNewRemoteLoadStatus(status)
  }

  // launch conditions
  private var isLoaderTaskFinished = false
  var isEmergencyLaunch = false
    private set

  fun onDidCreateReactInstanceManager(reactInstanceManager: ReactInstanceManager) {
    if (isEmergencyLaunch || !updatesConfiguration.isEnabled) {
      return
    }
    errorRecovery.startMonitoring(reactInstanceManager)
  }

  /**
   * If UpdatesController.initialize() is not provided with a [ReactApplication], this method
   * can be used to set a [ReactNativeHost] on the class. This is optional, but required in
   * order for `Updates.reload()` and some Updates module events to work.
   * @param reactNativeHost the ReactNativeHost of the application running the Updates module
   */
  fun setReactNativeHost(reactNativeHost: ReactNativeHost) {
    this.reactNativeHost = WeakReference(reactNativeHost)
  }

  /**
   * Returns the path on disk to the launch asset (JS bundle) file for the React Native host to use.
   * Blocks until the configured timeout runs out, or a new update has been downloaded and is ready
   * to use (whichever comes sooner). ReactNativeHost.getJSBundleFile() should call into this.
   *
   * If this returns null, something has gone wrong and expo-updates has not been able to launch or
   * find an update to use. In (and only in) this case, `getBundleAssetName()` will return a nonnull
   * fallback value to use.
   */
  @get:Synchronized
  val launchAssetFile: String?
    get() {
      while (!isLoaderTaskFinished) {
        try {
          (this as java.lang.Object).wait()
        } catch (e: InterruptedException) {
          Log.e(TAG, "Interrupted while waiting for launch asset file", e)
        }
      }
      return launcher?.launchAssetFile
    }

  /**
   * Returns the filename of the launch asset (JS bundle) file embedded in the APK bundle, which can
   * be read using `context.getAssets()`. This is only nonnull if `getLaunchAssetFile` is null and
   * should only be used in such a situation. ReactNativeHost.getBundleAssetName() should call into
   * this.
   */
  val bundleAssetName: String?
    get() = launcher?.bundleAssetName

  /**
   * Returns a map of the locally downloaded assets for the current update. Keys are the remote URLs
   * of the assets and values are local paths. This should be exported by the Updates JS module and
   * can be used by `expo-asset` or a similar module to override React Native's asset resolution and
   * use the locally downloaded assets.
   */
  val localAssetFiles: Map<AssetEntity, String>?
    get() = launcher?.localAssetFiles

  val isUsingEmbeddedAssets: Boolean
    get() = launcher?.isUsingEmbeddedAssets ?: false

  /**
   * Any process that calls this *must* manually release the lock by calling `releaseDatabase()` in
   * every possible case (success, error) as soon as it is finished.
   */
  private fun getDatabase(): UpdatesDatabase = databaseHolder.database

  private fun releaseDatabase() {
    databaseHolder.releaseDatabase()
  }

  val launchedUpdate: UpdateEntity?
    get() = launcher?.launchedUpdate

  private val selectionPolicy: SelectionPolicy
    get() = mSelectionPolicy ?: defaultSelectionPolicy

  // Internal Setters

  /**
   * For external modules that want to modify the selection policy used at runtime.
   *
   * This method does not provide any guarantees about how long the provided selection policy will
   * persist; sometimes expo-updates will reset the selection policy in situations where it makes
   * sense to have explicit control (e.g. if the developer/user has programmatically fetched an
   * update, expo-updates will reset the selection policy so the new update is launched on th
   * next reload).
   * @param selectionPolicy The SelectionPolicy to use next, until overridden by expo-updates
   */
  private fun setNextSelectionPolicy(selectionPolicy: SelectionPolicy?) {
    mSelectionPolicy = selectionPolicy
  }

  private fun resetSelectionPolicyToDefault() {
    mSelectionPolicy = null
  }

  private fun setDefaultSelectionPolicy(selectionPolicy: SelectionPolicy) {
    defaultSelectionPolicy = selectionPolicy
  }

  private fun setLauncher(launcher: Launcher?) {
    this.launcher = launcher
  }

  /**
   * Starts the update process to launch a previously-loaded update and (if configured to do so)
   * check for a new update from the server. This method should be called as early as possible in
   * the application's lifecycle.
   * @param context the base context of the application, ideally a [ReactApplication]
   */
  @Synchronized
  fun start(context: Context) {
    if (isStarted) {
      return
    }
    isStarted = true

    if (!updatesConfiguration.isEnabled) {
      launcher = NoDatabaseLauncher(context)
      notifyController()
      return
    }
    if (updatesConfiguration.updateUrl == null || updatesConfiguration.scopeKey == null) {
      throw AssertionError("expo-updates is enabled, but no valid URL is configured in AndroidManifest.xml. If you are making a release build for the first time, make sure you have run `expo publish` at least once.")
    }
    if (updatesDirectory == null) {
      launcher = NoDatabaseLauncher(context, updatesDirectoryException)
      isEmergencyLaunch = true
      notifyController()
      return
    }

    purgeUpdatesLogsOlderThanOneDay(context)

    initializeDatabaseHandler()
    initializeErrorRecovery(context)

    val databaseLocal = getDatabase()
    BuildData.ensureBuildDataIsConsistent(updatesConfiguration, databaseLocal)
    releaseDatabase()

    loaderTask = LoaderTask(
      updatesConfiguration,
      databaseHolder,
      updatesDirectory,
      fileDownloader,
      selectionPolicy,
      object : LoaderTaskCallback {
        override fun onFailure(e: Exception) {
          logger.error("UpdatesController loaderTask onFailure: ${e.localizedMessage}", UpdatesErrorCode.None)
          launcher = NoDatabaseLauncher(context, e)
          isEmergencyLaunch = true
          notifyController()
        }

        override fun onCachedUpdateLoaded(update: UpdateEntity): Boolean {
          return true
        }

        override fun onRemoteCheckForUpdateStarted() {
          stateMachine.processEvent(UpdatesStateEvent.Check())
        }

        override fun onRemoteCheckForUpdateFinished(result: LoaderTask.RemoteCheckResult) {
          val event = when (result) {
            is LoaderTask.RemoteCheckResult.NoUpdateAvailable -> UpdatesStateEvent.CheckCompleteUnavailable()
            is LoaderTask.RemoteCheckResult.UpdateAvailable -> UpdatesStateEvent.CheckCompleteWithUpdate(result.manifest)
            is LoaderTask.RemoteCheckResult.RollBackToEmbedded -> UpdatesStateEvent.CheckCompleteWithRollback(result.commitTime)
          }
          stateMachine.processEvent(event)
        }

        override fun onRemoteUpdateManifestResponseManifestLoaded(updateManifest: UpdateManifest) {
          remoteLoadStatus = ErrorRecoveryDelegate.RemoteLoadStatus.NEW_UPDATE_LOADING
        }

        override fun onSuccess(launcher: Launcher, isUpToDate: Boolean) {
          if (remoteLoadStatus == ErrorRecoveryDelegate.RemoteLoadStatus.NEW_UPDATE_LOADING && isUpToDate) {
            remoteLoadStatus = ErrorRecoveryDelegate.RemoteLoadStatus.IDLE
          }
          this@UpdatesController.launcher = launcher
          notifyController()
        }

        override fun onRemoteUpdateLoadStarted() {
          stateMachine.processEvent(UpdatesStateEvent.Download())
        }

        override fun onRemoteUpdateAssetLoaded(
          asset: AssetEntity,
          successfulAssetCount: Int,
          failedAssetCount: Int,
          totalAssetCount: Int
        ) {
          val body = mapOf(
            "assetInfo" to mapOf(
              "name" to asset.embeddedAssetFilename,
              "successfulAssetCount" to successfulAssetCount,
              "failedAssetCount" to failedAssetCount,
              "totalAssetCount" to totalAssetCount
            )
          )
          logger.info("AppController appLoaderTask didLoadAsset: $body", UpdatesErrorCode.None, null, asset.expectedHash)
        }

        override fun onRemoteUpdateFinished(
          status: RemoteUpdateStatus,
          update: UpdateEntity?,
          exception: Exception?
        ) {
          when (status) {
            RemoteUpdateStatus.ERROR -> {
              if (exception == null) {
                throw AssertionError("Background update with error status must have a nonnull exception object")
              }
              logger.error("UpdatesController onBackgroundUpdateFinished: Error: ${exception.localizedMessage}", UpdatesErrorCode.Unknown, exception)
              remoteLoadStatus = ErrorRecoveryDelegate.RemoteLoadStatus.IDLE
              val params = Arguments.createMap()
              params.putString("message", exception.message)
              sendLegacyUpdateEventToJS(UPDATE_ERROR_EVENT, params)

              // Since errors can happen through a number of paths, we do these checks
              // to make sure the state machine is valid
              when (stateMachine.state) {
                UpdatesStateValue.Idle -> {
                  stateMachine.processEvent(UpdatesStateEvent.Download())
                  stateMachine.processEvent(
                    UpdatesStateEvent.DownloadError(exception.message ?: "")
                  )
                }
                UpdatesStateValue.Checking -> {
                  stateMachine.processEvent(
                    UpdatesStateEvent.CheckError(exception.message ?: "")
                  )
                }
                else -> {
                  // .downloading
                  stateMachine.processEvent(
                    UpdatesStateEvent.DownloadError(exception.message ?: "")
                  )
                }
              }
            }
            RemoteUpdateStatus.UPDATE_AVAILABLE -> {
              if (update == null) {
                throw AssertionError("Background update with error status must have a nonnull update object")
              }
              remoteLoadStatus = ErrorRecoveryDelegate.RemoteLoadStatus.NEW_UPDATE_LOADED
              logger.info("UpdatesController onBackgroundUpdateFinished: Update available", UpdatesErrorCode.None)
              val params = Arguments.createMap()
              params.putString("manifestString", update.manifest.toString())
              sendLegacyUpdateEventToJS(UPDATE_AVAILABLE_EVENT, params)
              stateMachine.processEvent(
                UpdatesStateEvent.DownloadCompleteWithUpdate(update.manifest)
              )
            }
            RemoteUpdateStatus.NO_UPDATE_AVAILABLE -> {
              remoteLoadStatus = ErrorRecoveryDelegate.RemoteLoadStatus.IDLE
              logger.error("UpdatesController onBackgroundUpdateFinished: No update available", UpdatesErrorCode.NoUpdatesAvailable)
              sendLegacyUpdateEventToJS(UPDATE_NO_UPDATE_AVAILABLE_EVENT, null)
              // TODO: handle rollbacks properly, but this works for now
              if (stateMachine.state == UpdatesStateValue.Downloading) {
                stateMachine.processEvent(UpdatesStateEvent.DownloadComplete())
              }
            }
          }
          errorRecovery.notifyNewRemoteLoadStatus(remoteLoadStatus)
        }
      }
    )
    loaderTask!!.start(context)
  }

  @Synchronized
  private fun notifyController() {
    if (launcher == null) {
      throw AssertionError("UpdatesController.notifyController was called with a null launcher, which is an error. This method should only be called when an update is ready to launch.")
    }
    isLoaderTaskFinished = true
    (this as java.lang.Object).notify()
  }

  private fun initializeErrorRecovery(context: Context) {
    errorRecovery.initialize(object : ErrorRecoveryDelegate {
      override fun loadRemoteUpdate() {
        if (loaderTask?.isRunning == true) {
          return
        }
        remoteLoadStatus = ErrorRecoveryDelegate.RemoteLoadStatus.NEW_UPDATE_LOADING
        val database = getDatabase()
        val remoteLoader = RemoteLoader(context, updatesConfiguration, database, fileDownloader, updatesDirectory, launchedUpdate)
        remoteLoader.start(object : Loader.LoaderCallback {
          override fun onFailure(e: Exception) {
            logger.error("UpdatesController loadRemoteUpdate onFailure: ${e.localizedMessage}", UpdatesErrorCode.UpdateFailedToLoad, launchedUpdate?.loggingId, null)
            setRemoteLoadStatus(ErrorRecoveryDelegate.RemoteLoadStatus.IDLE)
            releaseDatabase()
          }

          override fun onSuccess(loaderResult: Loader.LoaderResult) {
            setRemoteLoadStatus(
              if (loaderResult.updateEntity != null || loaderResult.updateDirective is UpdateDirective.RollBackToEmbeddedUpdateDirective) ErrorRecoveryDelegate.RemoteLoadStatus.NEW_UPDATE_LOADED
              else ErrorRecoveryDelegate.RemoteLoadStatus.IDLE
            )
            releaseDatabase()
          }

          override fun onAssetLoaded(asset: AssetEntity, successfulAssetCount: Int, failedAssetCount: Int, totalAssetCount: Int) { }

          override fun onUpdateResponseLoaded(updateResponse: UpdateResponse): Loader.OnUpdateResponseLoadedResult {
            val updateDirective = updateResponse.directiveUpdateResponsePart?.updateDirective
            if (updateDirective != null) {
              return Loader.OnUpdateResponseLoadedResult(
                shouldDownloadManifestIfPresentInResponse = when (updateDirective) {
                  is UpdateDirective.RollBackToEmbeddedUpdateDirective -> false
                  is UpdateDirective.NoUpdateAvailableUpdateDirective -> false
                }
              )
            }

            val updateManifest = updateResponse.manifestUpdateResponsePart?.updateManifest ?: return Loader.OnUpdateResponseLoadedResult(shouldDownloadManifestIfPresentInResponse = false)
            return Loader.OnUpdateResponseLoadedResult(shouldDownloadManifestIfPresentInResponse = selectionPolicy.shouldLoadNewUpdate(updateManifest.updateEntity, launchedUpdate, updateResponse.responseHeaderData?.manifestFilters))
          }
        })
      }

      override fun relaunch(callback: LauncherCallback) { relaunchReactApplication(context, false, callback) }
      override fun throwException(exception: Exception) { throw exception }

      override fun markFailedLaunchForLaunchedUpdate() {
        if (isEmergencyLaunch) {
          return
        }
        databaseHandler.post {
          val launchedUpdate = launchedUpdate ?: return@post
          val database = getDatabase()
          database.updateDao().incrementFailedLaunchCount(launchedUpdate)
          releaseDatabase()
        }
      }

      override fun markSuccessfulLaunchForLaunchedUpdate() {
        if (isEmergencyLaunch) {
          return
        }
        databaseHandler.post {
          val launchedUpdate = launchedUpdate ?: return@post
          val database = getDatabase()
          database.updateDao().incrementSuccessfulLaunchCount(launchedUpdate)
          releaseDatabase()
        }
      }

      override fun getRemoteLoadStatus() = remoteLoadStatus
      override fun getCheckAutomaticallyConfiguration() = updatesConfiguration.checkOnLaunch
      override fun getLaunchedUpdateSuccessfulLaunchCount() = launchedUpdate?.successfulLaunchCount ?: 0
    })
  }

  private fun runReaper() {
    AsyncTask.execute {
      val databaseLocal = getDatabase()
      Reaper.reapUnusedUpdates(
        updatesConfiguration,
        databaseLocal,
        updatesDirectory,
        launchedUpdate,
        selectionPolicy
      )
      releaseDatabase()
    }
  }

  fun relaunchReactApplication(context: Context, callback: LauncherCallback) {
    relaunchReactApplication(context, true, callback)
  }

  private fun relaunchReactApplication(context: Context, shouldRunReaper: Boolean, callback: LauncherCallback) {
    val host = reactNativeHost?.get()
    if (host == null) {
      callback.onFailure(Exception("Could not reload application. Ensure you have passed the correct instance of ReactApplication into UpdatesController.initialize()."))
      return
    }

    stateMachine.processEvent(UpdatesStateEvent.Restart())

    val oldLaunchAssetFile = launcher!!.launchAssetFile

    val databaseLocal = getDatabase()
    val newLauncher = DatabaseLauncher(
      updatesConfiguration,
      updatesDirectory!!,
      fileDownloader,
      selectionPolicy
    )
    newLauncher.launch(
      databaseLocal, context,
      object : LauncherCallback {
        override fun onFailure(e: Exception) {
          callback.onFailure(e)
        }

        override fun onSuccess() {
          launcher = newLauncher
          releaseDatabase()

          val instanceManager = host.reactInstanceManager

          val newLaunchAssetFile = launcher!!.launchAssetFile
          if (newLaunchAssetFile != null && newLaunchAssetFile != oldLaunchAssetFile) {
            // Unfortunately, even though RN exposes a way to reload an application,
            // it assumes that the JS bundle will stay at the same location throughout
            // the entire lifecycle of the app. Since we need to change the location of
            // the bundle, we need to use reflection to set an otherwise inaccessible
            // field of the ReactInstanceManager.
            try {
              val newJSBundleLoader = JSBundleLoader.createFileLoader(newLaunchAssetFile)
              val jsBundleLoaderField = instanceManager.javaClass.getDeclaredField("mBundleLoader")
              jsBundleLoaderField.isAccessible = true
              jsBundleLoaderField[instanceManager] = newJSBundleLoader
            } catch (e: Exception) {
              Log.e(TAG, "Could not reset JSBundleLoader in ReactInstanceManager", e)
            }
          }
          callback.onSuccess()
          val handler = Handler(Looper.getMainLooper())
          handler.post { instanceManager.recreateReactContextInBackground() }
          if (shouldRunReaper) {
            runReaper()
          }
          stateMachine.reset()
        }
      }
    )
  }

  override fun sendUpdateStateChangeEventToBridge(eventType: UpdatesStateEventType, context: UpdatesStateContext) {
    sendEventToJS(UPDATES_STATE_CHANGE_EVENT_NAME, eventType.type, context.writableMap)
  }

  private fun sendLegacyUpdateEventToJS(eventType: String, params: WritableMap?) {
    sendEventToJS(UPDATES_EVENT_NAME, eventType, params)
  }

  private fun sendEventToJS(eventName: String, eventType: String, params: WritableMap?) {
    UpdatesUtils.sendEventToReactNative(reactNativeHost, logger, eventName, eventType, params)
  }

  fun getNativeStateMachineContext(): UpdatesStateContext {
    return stateMachine.context
  }

  sealed class CheckForUpdateResult(private val status: Status) {
    private enum class Status {
      NO_UPDATE_AVAILABLE,
      UPDATE_AVAILABLE,
      ROLL_BACK_TO_EMBEDDED,
      ERROR
    }

    class NoUpdateAvailable(val reason: LoaderTask.RemoteCheckResultNotAvailableReason) : CheckForUpdateResult(Status.NO_UPDATE_AVAILABLE)
    class UpdateAvailable(val updateManifest: UpdateManifest) : CheckForUpdateResult(Status.UPDATE_AVAILABLE)
    class RollBackToEmbedded(val commitTime: Date) : CheckForUpdateResult(Status.ROLL_BACK_TO_EMBEDDED)
    class ErrorResult(val error: Exception, val message: String) : CheckForUpdateResult(Status.ERROR)
  }

  fun checkForUpdate(context: Context, callback: (result: CheckForUpdateResult) -> Unit) {
    stateMachine.processEvent(UpdatesStateEvent.Check())

    AsyncTask.execute {
      val embeddedUpdate = EmbeddedManifest.get(context, updatesConfiguration)?.updateEntity
      val extraHeaders = FileDownloader.getExtraHeadersForRemoteUpdateRequest(
        databaseHolder.database,
        updatesConfiguration,
        launchedUpdate,
        embeddedUpdate
      )
      databaseHolder.releaseDatabase()
      fileDownloader.downloadRemoteUpdate(
        updatesConfiguration,
        extraHeaders,
        context,
        object : FileDownloader.RemoteUpdateDownloadCallback {
          override fun onFailure(message: String, e: Exception) {
            stateMachine.processEvent(UpdatesStateEvent.CheckError(message))
            callback(CheckForUpdateResult.ErrorResult(e, message))
          }

          override fun onSuccess(updateResponse: UpdateResponse) {
            val updateDirective = updateResponse.directiveUpdateResponsePart?.updateDirective
            val updateManifest = updateResponse.manifestUpdateResponsePart?.updateManifest

            if (updateDirective != null) {
              if (updateDirective is UpdateDirective.RollBackToEmbeddedUpdateDirective) {
                if (!updatesConfiguration.hasEmbeddedUpdate) {
                  callback(CheckForUpdateResult.NoUpdateAvailable(LoaderTask.RemoteCheckResultNotAvailableReason.ROLLBACK_NO_EMBEDDED))
                  stateMachine.processEvent(UpdatesStateEvent.CheckCompleteUnavailable())
                  return
                }

                if (embeddedUpdate == null) {
                  callback(CheckForUpdateResult.NoUpdateAvailable(LoaderTask.RemoteCheckResultNotAvailableReason.ROLLBACK_NO_EMBEDDED))
                  stateMachine.processEvent(UpdatesStateEvent.CheckCompleteUnavailable())
                  return
                }

                if (!selectionPolicy.shouldLoadRollBackToEmbeddedDirective(
                    updateDirective,
                    embeddedUpdate,
                    launchedUpdate,
                    updateResponse.responseHeaderData?.manifestFilters
                  )
                ) {
                  callback(CheckForUpdateResult.NoUpdateAvailable(LoaderTask.RemoteCheckResultNotAvailableReason.ROLLBACK_REJECTED_BY_SELECTION_POLICY))
                  stateMachine.processEvent(UpdatesStateEvent.CheckCompleteUnavailable())
                  return
                }

                callback(CheckForUpdateResult.RollBackToEmbedded(updateDirective.commitTime))
                stateMachine.processEvent(UpdatesStateEvent.CheckCompleteWithRollback(updateDirective.commitTime))
                return
              }
            }

            if (updateManifest == null) {
              callback(CheckForUpdateResult.NoUpdateAvailable(LoaderTask.RemoteCheckResultNotAvailableReason.NO_UPDATE_AVAILABLE_ON_SERVER))
              UpdatesStateEvent.CheckCompleteUnavailable()
              return
            }

            if (launchedUpdate == null) {
              // this shouldn't ever happen, but if we don't have anything to compare
              // the new manifest to, let the user know an update is available
              callback(CheckForUpdateResult.UpdateAvailable(updateManifest))
              stateMachine.processEvent(UpdatesStateEvent.CheckCompleteWithUpdate(updateManifest.manifest.getRawJson()))
              return
            }

            var shouldLaunch = false
            var failedPreviously = false
            if (selectionPolicy.shouldLoadNewUpdate(
                updateManifest.updateEntity,
                launchedUpdate,
                updateResponse.responseHeaderData?.manifestFilters
              )
            ) {
              // If "update" has failed to launch previously, then
              // "launchedUpdate" will be an earlier update, and the test above
              // will return true (incorrectly).
              // We check to see if the new update is already in the DB, and if so,
              // only allow the update if it has had no launch failures.
              shouldLaunch = true
              updateManifest.updateEntity?.let { updateEntity ->
                val storedUpdateEntity = databaseHolder.database.updateDao().loadUpdateWithId(
                  updateEntity.id
                )
                databaseHolder.releaseDatabase()
                storedUpdateEntity?.let { storedUpdateEntity ->
                  shouldLaunch = storedUpdateEntity.failedLaunchCount == 0
                  logger.info("Stored update found: ID = ${updateEntity.id}, failureCount = ${storedUpdateEntity.failedLaunchCount}")
                  failedPreviously = !shouldLaunch
                }
              }
            }
            if (shouldLaunch) {
              callback(CheckForUpdateResult.UpdateAvailable(updateManifest))
              stateMachine.processEvent(UpdatesStateEvent.CheckCompleteWithUpdate(updateManifest.manifest.getRawJson()))
              return
            } else {
              val reason = when (failedPreviously) {
                true -> LoaderTask.RemoteCheckResultNotAvailableReason.UPDATE_PREVIOUSLY_FAILED
                else -> LoaderTask.RemoteCheckResultNotAvailableReason.UPDATE_REJECTED_BY_SELECTION_POLICY
              }
              callback(CheckForUpdateResult.NoUpdateAvailable(reason))
              stateMachine.processEvent(UpdatesStateEvent.CheckCompleteUnavailable())
              return
            }
          }
        }
      )
    }
  }

  sealed class FetchUpdateResult(private val status: Status) {
    private enum class Status {
      SUCCESS,
      FAILURE,
      ROLL_BACK_TO_EMBEDDED,
      ERROR
    }

    class Success(val update: UpdateEntity) : FetchUpdateResult(Status.SUCCESS)
    class Failure : FetchUpdateResult(Status.FAILURE)
    class RollBackToEmbedded : FetchUpdateResult(Status.ROLL_BACK_TO_EMBEDDED)
    class ErrorResult(val error: Exception) : FetchUpdateResult(Status.ERROR)
  }

  fun fetchUpdate(context: Context, callback: (result: FetchUpdateResult) -> Unit) {
    stateMachine.processEvent(UpdatesStateEvent.Download())

    AsyncTask.execute {
      val database = databaseHolder.database
      RemoteLoader(
        context,
        updatesConfiguration,
        database,
        fileDownloader,
        updatesDirectory,
        launchedUpdate
      )
        .start(
          object : Loader.LoaderCallback {
            override fun onFailure(e: Exception) {
              databaseHolder.releaseDatabase()
              callback(FetchUpdateResult.ErrorResult(e))
              stateMachine.processEvent(
                UpdatesStateEvent.DownloadError("Failed to download new update: ${e.message}")
              )
            }

            override fun onAssetLoaded(
              asset: AssetEntity,
              successfulAssetCount: Int,
              failedAssetCount: Int,
              totalAssetCount: Int
            ) {
            }

            override fun onUpdateResponseLoaded(updateResponse: UpdateResponse): Loader.OnUpdateResponseLoadedResult {
              val updateDirective = updateResponse.directiveUpdateResponsePart?.updateDirective
              if (updateDirective != null) {
                return Loader.OnUpdateResponseLoadedResult(
                  shouldDownloadManifestIfPresentInResponse = when (updateDirective) {
                    is UpdateDirective.RollBackToEmbeddedUpdateDirective -> false
                    is UpdateDirective.NoUpdateAvailableUpdateDirective -> false
                  }
                )
              }

              val updateManifest = updateResponse.manifestUpdateResponsePart?.updateManifest
                ?: return Loader.OnUpdateResponseLoadedResult(shouldDownloadManifestIfPresentInResponse = false)

              return Loader.OnUpdateResponseLoadedResult(
                shouldDownloadManifestIfPresentInResponse = selectionPolicy.shouldLoadNewUpdate(
                  updateManifest.updateEntity,
                  launchedUpdate,
                  updateResponse.responseHeaderData?.manifestFilters
                )
              )
            }

            override fun onSuccess(loaderResult: Loader.LoaderResult) {
              RemoteLoader.processSuccessLoaderResult(
                context,
                updatesConfiguration,
                database,
                selectionPolicy,
                updatesDirectory,
                launchedUpdate,
                loaderResult
              ) { availableUpdate, didRollBackToEmbedded ->
                databaseHolder.releaseDatabase()

                if (didRollBackToEmbedded) {
                  callback(FetchUpdateResult.RollBackToEmbedded())
                  stateMachine.processEvent(UpdatesStateEvent.DownloadCompleteWithRollback())
                } else {
                  if (availableUpdate == null) {
                    callback(FetchUpdateResult.Failure())
                    stateMachine.processEvent(UpdatesStateEvent.DownloadComplete())
                  } else {
                    resetSelectionPolicyToDefault()

                    // We need the explicit casting here because when in versioned expo-updates,
                    // the UpdateEntity and UpdatesModule are in different package namespace,
                    // Kotlin cannot do the smart casting for that case.
                    val updateEntity = loaderResult.updateEntity as UpdateEntity

                    callback(FetchUpdateResult.Success(updateEntity))
                    stateMachine.processEvent(UpdatesStateEvent.DownloadCompleteWithUpdate(updateEntity.manifest))
                  }
                }
              }
            }
          }
        )
    }
  }

  interface GetExtraParamsCallback {
    fun onFailure(e: Exception)
    fun onSuccess(paramsBundle: Bundle)
  }

  fun getExtraParams(callback: GetExtraParamsCallback) {
    AsyncTask.execute {
      try {
        val result = ManifestMetadata.getExtraParams(
          databaseHolder.database,
          updatesConfiguration,
        )
        databaseHolder.releaseDatabase()
        val resultMap = when (result) {
          null -> Bundle()
          else -> {
            Bundle().apply {
              result.forEach {
                putString(it.key, it.value)
              }
            }
          }
        }
        callback.onSuccess(resultMap)
      } catch (e: Exception) {
        databaseHolder.releaseDatabase()
        callback.onFailure(e)
      }
    }
  }

  interface SetExtraParamsCallback {
    fun onFailure(e: Exception)
    fun onSuccess()
  }

  fun setExtraParam(key: String, value: String?, callback: SetExtraParamsCallback) {
    AsyncTask.execute {
      try {
        ManifestMetadata.setExtraParam(
          databaseHolder.database,
          updatesConfiguration,
          key,
          value
        )
        databaseHolder.releaseDatabase()
        callback.onSuccess()
      } catch (e: Exception) {
        databaseHolder.releaseDatabase()
        callback.onFailure(e)
      }
    }
  }

  companion object {
    private val TAG = UpdatesController::class.java.simpleName

    private const val UPDATE_AVAILABLE_EVENT = "updateAvailable"
    private const val UPDATE_NO_UPDATE_AVAILABLE_EVENT = "noUpdateAvailable"
    private const val UPDATE_ERROR_EVENT = "error"

    private const val UPDATES_EVENT_NAME = "Expo.nativeUpdatesEvent"
    private const val UPDATES_STATE_CHANGE_EVENT_NAME = "Expo.nativeUpdatesStateChangeEvent"

    private var singletonInstance: UpdatesController? = null
    @JvmStatic val instance: UpdatesController
      get() {
        return checkNotNull(singletonInstance) { "UpdatesController.instance was called before the module was initialized" }
      }

    @JvmStatic fun initializeWithoutStarting(context: Context) {
      if (singletonInstance == null) {
        val updatesConfiguration = UpdatesConfiguration(context, null)
        singletonInstance = UpdatesController(context, updatesConfiguration)
      }
    }

    /**
     * Initializes the UpdatesController singleton. This should be called as early as possible in the
     * application's lifecycle.
     * @param context the base context of the application, ideally a [ReactApplication]
     */
    @JvmStatic fun initialize(context: Context) {
      if (singletonInstance == null) {
        initializeWithoutStarting(context)
        singletonInstance!!.start(context)
      }
    }

    /**
     * Initializes the UpdatesController singleton. This should be called as early as possible in the
     * application's lifecycle. Use this method to set or override configuration values at runtime
     * rather than from AndroidManifest.xml.
     * @param context the base context of the application, ideally a [ReactApplication]
     */
    @JvmStatic fun initialize(context: Context, configuration: Map<String, Any>) {
      if (singletonInstance == null) {
        val updatesConfiguration = UpdatesConfiguration(context, configuration)
        singletonInstance = UpdatesController(context, updatesConfiguration)
        singletonInstance!!.start(context)
      }
    }
  }

  init {
    try {
      updatesDirectory = UpdatesUtils.getOrCreateUpdatesDirectory(context)
    } catch (e: Exception) {
      updatesDirectoryException = e
      updatesDirectory = null
    }
  }

  /**
   * Main entry point to expo-updates in development builds with expo-dev-client. Singleton that still
   * makes use of [UpdatesController] for keeping track of updates state, but provides capabilities
   * that are not usually exposed but that expo-dev-client needs (launching and downloading a specific
   * update by URL, allowing dynamic configuration, introspecting the database).
   *
   * Implements the external UpdatesInterface from the expo-updates-interface package. This allows
   * expo-dev-client to compile without needing expo-updates to be installed.
   */
  class UpdatesDevLauncherController : UpdatesInterface {
    private var mTempConfiguration: UpdatesConfiguration? = null
    override fun reset() {
      UpdatesController.instance.setLauncher(null)
    }

    /**
     * Fetch an update using a dynamically generated configuration object (including a potentially
     * different update URL than the one embedded in the build).
     */
    override fun fetchUpdateWithConfiguration(
      configuration: HashMap<String, Any>,
      context: Context,
      callback: UpdatesInterface.UpdateCallback
    ) {
      val controller = UpdatesController.instance
      val updatesConfiguration = UpdatesConfiguration(context, configuration)
      if (updatesConfiguration.updateUrl == null || updatesConfiguration.scopeKey == null) {
        callback.onFailure(Exception("Failed to load update: UpdatesConfiguration object must include a valid update URL"))
        return
      }
      if (controller.updatesDirectory == null) {
        callback.onFailure(controller.updatesDirectoryException)
        return
      }

      // since controller is a singleton, save its config so we can reset to it if our request fails
      mTempConfiguration = controller.updatesConfiguration
      setDevelopmentSelectionPolicy()
      controller.updatesConfiguration = updatesConfiguration
      val databaseHolder = controller.databaseHolder
      val loader = RemoteLoader(
        context,
        updatesConfiguration,
        databaseHolder.database,
        controller.fileDownloader,
        controller.updatesDirectory,
        null
      )
      loader.start(object : Loader.LoaderCallback {
        override fun onFailure(e: Exception) {
          databaseHolder.releaseDatabase()
          // reset controller's configuration to what it was before this request
          controller.updatesConfiguration = mTempConfiguration!!
          callback.onFailure(e)
        }

        override fun onSuccess(loaderResult: Loader.LoaderResult) {
          // the dev launcher doesn't handle roll back to embedded commands
          databaseHolder.releaseDatabase()
          if (loaderResult.updateEntity == null) {
            callback.onSuccess(null)
            return
          }
          launchUpdate(loaderResult.updateEntity, updatesConfiguration, context, callback)
        }

        override fun onAssetLoaded(
          asset: AssetEntity,
          successfulAssetCount: Int,
          failedAssetCount: Int,
          totalAssetCount: Int
        ) {
          callback.onProgress(successfulAssetCount, failedAssetCount, totalAssetCount)
        }

        override fun onUpdateResponseLoaded(updateResponse: UpdateResponse): Loader.OnUpdateResponseLoadedResult {
          val updateDirective = updateResponse.directiveUpdateResponsePart?.updateDirective
          if (updateDirective != null) {
            return Loader.OnUpdateResponseLoadedResult(
              shouldDownloadManifestIfPresentInResponse = when (updateDirective) {
                is UpdateDirective.RollBackToEmbeddedUpdateDirective -> false
                is UpdateDirective.NoUpdateAvailableUpdateDirective -> false
              }
            )
          }

          val updateManifest = updateResponse.manifestUpdateResponsePart?.updateManifest ?: return Loader.OnUpdateResponseLoadedResult(shouldDownloadManifestIfPresentInResponse = false)
          return Loader.OnUpdateResponseLoadedResult(shouldDownloadManifestIfPresentInResponse = callback.onManifestLoaded(updateManifest.manifest.getRawJson()))
        }
      })
    }

    private fun launchUpdate(
      update: UpdateEntity,
      configuration: UpdatesConfiguration,
      context: Context,
      callback: UpdatesInterface.UpdateCallback
    ) {
      val controller = UpdatesController.instance

      // ensure that we launch the update we want, even if it isn't the latest one
      val currentSelectionPolicy = controller.selectionPolicy
      // Calling `setNextSelectionPolicy` allows the Updates module's `reloadAsync` method to reload
      // with a different (newer) update if one is downloaded, e.g. using `fetchUpdateAsync`. If we
      // set the default selection policy here instead, the update we are launching here would keep
      // being launched by `reloadAsync` even if a newer one is downloaded.
      controller.setNextSelectionPolicy(
        SelectionPolicy(
          LauncherSelectionPolicySingleUpdate(update.id),
          currentSelectionPolicy.loaderSelectionPolicy,
          currentSelectionPolicy.reaperSelectionPolicy
        )
      )

      val databaseHolder = controller.databaseHolder
      val launcher = DatabaseLauncher(
        configuration,
        controller.updatesDirectory!!,
        controller.fileDownloader,
        controller.selectionPolicy
      )
      launcher.launch(
        databaseHolder.database, context,
        object : LauncherCallback {
          override fun onFailure(e: Exception) {
            databaseHolder.releaseDatabase()
            // reset controller's configuration to what it was before this request
            controller.updatesConfiguration = mTempConfiguration!!
            callback.onFailure(e)
          }

          override fun onSuccess() {
            databaseHolder.releaseDatabase()
            controller.setLauncher(launcher)
            callback.onSuccess(object : UpdatesInterface.Update {
              override fun getManifest(): JSONObject {
                return launcher.launchedUpdate!!.manifest
              }

              override fun getLaunchAssetPath(): String {
                return launcher.launchAssetFile!!
              }
            })
            controller.runReaper()
          }
        }
      )
    }

    companion object {
      private var singletonInstance: UpdatesDevLauncherController? = null
      val instance: UpdatesDevLauncherController
        get() {
          return checkNotNull(singletonInstance) { "UpdatesDevLauncherController.instance was called before the module was initialized" }
        }

      @JvmStatic fun initialize(context: Context): UpdatesDevLauncherController {
        if (singletonInstance == null) {
          singletonInstance = UpdatesDevLauncherController()
        }
        initializeWithoutStarting(context)
        return instance
      }

      private fun setDevelopmentSelectionPolicy() {
        val controller = UpdatesController.instance
        controller.resetSelectionPolicyToDefault()
        val currentSelectionPolicy = controller.selectionPolicy
        controller.setDefaultSelectionPolicy(
          SelectionPolicy(
            currentSelectionPolicy.launcherSelectionPolicy,
            currentSelectionPolicy.loaderSelectionPolicy,
            ReaperSelectionPolicyDevelopmentClient()
          )
        )
        controller.resetSelectionPolicyToDefault()
      }
    }
  }
}
