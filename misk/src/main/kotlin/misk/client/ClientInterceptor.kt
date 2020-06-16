package misk.client

import okhttp3.Interceptor

object ClientInterceptors {

  /**
   * This interface is used with Guice multibindings. Register instances by calling `multibind()`
   * in a `KAbstractModule`:
   *
   * ```
   * multibind<ClientInterceptors.Factory>().to<MyFactory>()
   * ```
   */
  interface Factory {
    fun create(action: ClientAction): Interceptor?
  }
}
