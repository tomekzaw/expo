import { NativeDatabase } from './NativeDatabase';
import {
  BindParams,
  BindValue,
  NativeStatement,
  RunResult,
  VariadicBindParams,
} from './NativeStatement';

export { BindParams, BindValue, RunResult, VariadicBindParams };

/**
 * A prepared statement returned by [`Database.prepareAsync()`](#prepareasyncsource) or [`Database.prepareSync()`](#preparesyncsource) that can be binded with parameters and executed.
 */
export class Statement {
  constructor(
    private readonly nativeDatabase: NativeDatabase,
    private readonly nativeStatement: NativeStatement
  ) {}

  //#region Asynchronous API

  /**
   * Run the prepared statement and return the result.
   * @param params The variadic parameters to bind to the prepared statement. You can also pass an array of values.
   * @see [`BindValue`](#bindvalue) for more information about binding values.
   * @example
   * ```ts
   *   statement.runAsync(1, 'Hello');
   *   statement.runAsync([1, 'Hello']); // You can also pass an array of values.
   * ```
   */
  public runAsync(...params: VariadicBindParams): Promise<RunResult>;
  /**
   * Run the prepared statement and return the result.
   * @param params The map parameters to bind to the prepared statement.
   * @see [`BindValue`](#bindvalue) for more information about binding values.
   * @example
   * ```ts
   * statement.runAsync({ $action: 'Hello' });
   * ```
   */
  public runAsync(params: BindParams): Promise<RunResult>;
  public async runAsync(...params: unknown[]): Promise<RunResult> {
    const { params: bindParams, shouldPassAsObject } = normalizeParams(...params);
    if (shouldPassAsObject) {
      return await this.nativeStatement.objectRunAsync(this.nativeDatabase, bindParams);
    } else {
      return await this.nativeStatement.arrayRunAsync(this.nativeDatabase, bindParams);
    }
  }

  /**
   * Iterate the prepared statement and return results as an async iterable.
   * @param params The variadic parameters to bind to the prepared statement. You can also pass an array of values.
   * @see [`BindValue`](#bindvalue) for more information about binding values.
   * @example
   * ```ts
   * const statement = await db.prepareAsync('SELECT * FROM test');
   * for await (const row of statement.eachAsync<any>()) {
   *   console.log(row);
   * }
   * ```
   */
  public eachAsync<T>(...params: VariadicBindParams): AsyncIterableIterator<T>;
  /**
   * Iterate the prepared statement and return results as an async iterable.
   * @param params The map parameters to bind to the prepared statement.
   * @see [`BindValue`](#bindvalue) for more information about binding values.
   * @example
   * ```ts
   * const statement = await db.prepareAsync('SELECT * FROM test WHERE value >= $value');
   * for await (const row of statement.eachAsync<any>({ $value: 100 })) {
   *   console.log(row);
   * }
   * ```
   */
  public eachAsync<T>(params: BindParams): AsyncIterableIterator<T>;
  public async *eachAsync<T>(...params: unknown[]): AsyncIterableIterator<T> {
    const { params: bindParams, shouldPassAsObject } = normalizeParams(...params);
    const func = shouldPassAsObject
      ? this.nativeStatement.objectGetAsync.bind(this.nativeStatement)
      : this.nativeStatement.arrayGetAsync.bind(this.nativeStatement);

    let result: T | null = null;
    do {
      result = await func(this.nativeDatabase, bindParams);
      if (result != null) {
        yield result;
      }
    } while (result != null);
  }

  /**
   * Get one row from the prepared statement.
   * @param params The variadic parameters to bind to the prepared statement. You can also pass an array of values.
   * @see [`BindValue`](#bindvalue) for more information about binding values.
   */
  public getAsync<T>(...params: VariadicBindParams): Promise<T | null>;
  /*
   * Get one row from the prepared statement.
   * @param params The map parameters to bind to the prepared statement.
   * @see [`BindValue`](#bindvalue) for more information about binding values.
   */
  public getAsync<T>(params: BindParams): Promise<T | null>;
  public async getAsync<T>(...params: unknown[]): Promise<T | null> {
    const { params: bindParams, shouldPassAsObject } = normalizeParams(...params);
    if (shouldPassAsObject) {
      return (await this.nativeStatement.objectGetAsync(this.nativeDatabase, bindParams)) ?? null;
    } else {
      return (await this.nativeStatement.arrayGetAsync(this.nativeDatabase, bindParams)) ?? null;
    }
  }

  /**
   * Get all rows from the prepared statement.
   * @param params The variadic parameters to bind to the prepared statement. You can also pass an array of values.
   * @see [`BindValue`](#bindvalue) for more information about binding values.
   */
  public allAsync<T>(...params: VariadicBindParams): Promise<T[]>;
  /**
   * Get all rows from the prepared statement.
   * @param params The map parameters to bind to the prepared statement.
   * @see [`BindValue`](#bindvalue) for more information about binding values.
   */
  public allAsync<T>(params: BindParams): Promise<T[]>;
  public async allAsync<T>(...params: unknown[]): Promise<T[]> {
    const { params: bindParams, shouldPassAsObject } = normalizeParams(...params);
    if (shouldPassAsObject) {
      return await this.nativeStatement.objectGetAllAsync(this.nativeDatabase, bindParams);
    } else {
      return await this.nativeStatement.arrayGetAllAsync(this.nativeDatabase, bindParams);
    }
  }

  /**
   * Reset the prepared statement cursor.
   */
  public async resetAsync(): Promise<void> {
    await this.nativeStatement.resetAsync(this.nativeDatabase);
  }

  /**
   * Finalize the prepared statement.
   * > **Note:** Remember to finalize the prepared statement whenever you call `prepareAsync()` to avoid resource leaks.
   */
  public async finalizeAsync(): Promise<void> {
    await this.nativeStatement.finalizeAsync(this.nativeDatabase);
  }

  //#endregion

  //#region Synchronous API

  /**
   * Run the prepared statement and return the result.
   * > **Note:** Running heavy tasks with this function can block the JavaScript thread and affect performance.
   * @param params The variadic parameters to bind to the prepared statement. You can also pass an array of values.
   * @see [`BindValue`](#bindvalue) for more information about binding values.
   */
  public runSync(...params: VariadicBindParams): RunResult;
  /**
   * Run the prepared statement and return the result.
   * > **Note:** Running heavy tasks with this function can block the JavaScript thread and affect performance.
   * @param params The map parameters to bind to the prepared statement.
   * @see [`BindValue`](#bindvalue) for more information about binding values.
   */
  public runSync(params: BindParams): RunResult;
  public runSync(...params: unknown[]): RunResult {
    const { params: bindParams, shouldPassAsObject } = normalizeParams(...params);
    if (shouldPassAsObject) {
      return this.nativeStatement.objectRunSync(this.nativeDatabase, bindParams);
    } else {
      return this.nativeStatement.arrayRunSync(this.nativeDatabase, bindParams);
    }
  }

  /**
   * Iterate the prepared statement and return results as an iterable.
   * > **Note:** Running heavy tasks with this function can block the JavaScript thread and affect performance.
   * @param params The variadic parameters to bind to the prepared statement. You can also pass an array of values.
   * @see [`BindValue`](#bindvalue) for more information about binding values.
   */
  public eachSync<T>(...params: VariadicBindParams): IterableIterator<T>;
  /**
   * Iterate the prepared statement and return results as an iterable.
   * > **Note:** Running heavy tasks with this function can block the JavaScript thread and affect performance.
   * @param params The map parameters to bind to the prepared statement.
   * @see [`BindValue`](#bindvalue) for more information about binding values.
   */
  public eachSync<T>(params: BindParams): IterableIterator<T>;
  public *eachSync<T>(...params: unknown[]): IterableIterator<T> {
    const { params: bindParams, shouldPassAsObject } = normalizeParams(...params);
    const func = shouldPassAsObject
      ? this.nativeStatement.objectGetSync.bind(this.nativeStatement)
      : this.nativeStatement.arrayGetSync.bind(this.nativeStatement);

    let result: T | null = null;
    do {
      result = func(this.nativeDatabase, bindParams);
      if (result != null) {
        yield result;
      }
    } while (result != null);
  }

  /**
   * Get one row from the prepared statement.
   * > **Note:** Running heavy tasks with this function can block the JavaScript thread and affect performance.
   * @param params The variadic parameters to bind to the prepared statement. You can also pass an array of values.
   * @see [`BindValue`](#bindvalue) for more information about binding values.
   */
  public getSync<T>(...params: VariadicBindParams): T | null;
  /**
   * Get one row from the prepared statement.
   * > **Note:** Running heavy tasks with this function can block the JavaScript thread and affect performance.
   * @param params The map parameters to bind to the prepared statement.
   * @see [`BindValue`](#bindvalue) for more information about binding values.
   */
  public getSync<T>(params: BindParams): T | null;
  public getSync<T>(...params: unknown[]): T | null {
    const { params: bindParams, shouldPassAsObject } = normalizeParams(...params);
    if (shouldPassAsObject) {
      return this.nativeStatement.objectGetSync(this.nativeDatabase, bindParams) ?? null;
    } else {
      return this.nativeStatement.arrayGetSync(this.nativeDatabase, bindParams) ?? null;
    }
  }

  /**
   * Get all rows from the prepared statement.
   * > **Note:** Running heavy tasks with this function can block the JavaScript thread and affect performance.
   * @param params The variadic parameters to bind to the prepared statement. You can also pass an array of values.
   * @see [`BindValue`](#bindvalue) for more information about binding values.
   */
  public allSync<T>(...params: VariadicBindParams): T[];
  /**
   * Get all rows from the prepared statement.
   * > **Note:** Running heavy tasks with this function can block the JavaScript thread and affect performance.
   * @param params The map parameters to bind to the prepared statement.
   * @see [`BindValue`](#bindvalue) for more information about binding values.
   */
  public allSync<T>(params: BindParams): T[];
  public allSync<T>(...params: unknown[]): T[] {
    const { params: bindParams, shouldPassAsObject } = normalizeParams(...params);
    if (shouldPassAsObject) {
      return this.nativeStatement.objectGetAllSync(this.nativeDatabase, bindParams);
    } else {
      return this.nativeStatement.arrayGetAllSync(this.nativeDatabase, bindParams);
    }
  }

  /**
   * Reset the prepared statement cursor.
   */
  public resetSync(): void {
    this.nativeStatement.resetSync(this.nativeDatabase);
  }

  /**
   * Finalize the prepared statement.
   *
   * > **Note:** Remember to finalize the prepared statement whenever you call `prepareSync()` to avoid resource leaks.
   *
   */
  public finalizeSync(): void {
    this.nativeStatement.finalizeSync(this.nativeDatabase);
  }

  //#endregion
}

/**
 * Normalize the bind params to an array or object.
 * @hidden
 */
export function normalizeParams(...params: any[]): {
  params: BindParams;
  shouldPassAsObject: boolean;
} {
  let bindParams = params.length > 1 ? params : (params[0] as BindParams);
  if (typeof bindParams !== 'object') {
    bindParams = [bindParams];
  }
  const shouldPassAsObject = !Array.isArray(bindParams);
  return {
    params: bindParams,
    shouldPassAsObject,
  };
}
