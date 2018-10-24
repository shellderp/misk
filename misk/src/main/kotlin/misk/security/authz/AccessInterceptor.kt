package misk.security.authz

import misk.Action
import misk.ApplicationInterceptor
import misk.Chain
import misk.MiskCaller
import misk.exceptions.UnauthenticatedException
import misk.exceptions.UnauthorizedException
import misk.scope.ActionScoped
import javax.inject.Inject
import kotlin.reflect.KClass

internal class AccessInterceptor private constructor(
  private val allowedServices: Set<String>,
  private val allowedRoles: Set<String>,
  private val caller: ActionScoped<MiskCaller?>
) : ApplicationInterceptor {

  override fun intercept(chain: Chain): Any {
    val caller = caller.get() ?: throw UnauthenticatedException()
    if (!isAllowed(caller)) {
      throw UnauthorizedException()
    }

    return chain.proceed(chain.args)
  }

  private fun isAllowed(caller: MiskCaller): Boolean {
    // Allow if we don't have any requirements on service or role
    if (allowedServices.isEmpty() && allowedRoles.isEmpty()) return true

    // Allow if the caller has provided an allowed service
    if (caller.service != null && allowedServices.contains(caller.service)) return true

    // Allow if the caller has provided an allowed role
    return caller.roles.any { allowedRoles.contains(it) }
  }

  internal class Factory @Inject internal constructor(
    private val caller: @JvmSuppressWildcards ActionScoped<MiskCaller?>,
    private val registeredEntries: List<AccessAnnotationEntry>
  ) : ApplicationInterceptor.Factory {
    override fun create(action: Action): ApplicationInterceptor? {
      // Gather all of the access annotations on this action.
      val actionEntries = mutableListOf<AccessAnnotationEntry>()
      for (annotation in action.function.annotations) when {
        annotation is Authenticated -> actionEntries += annotation.toAccessAnnotationEntry()
        annotation is Unauthenticated -> actionEntries += annotation.toAccessAnnotationEntry()
        registeredEntries.find { it.annotation == annotation.annotationClass } != null -> {
          actionEntries += registeredEntries.find { it.annotation == annotation.annotationClass }!!
        }
      }

      when {
        // There should only be one access annotation on the action
        actionEntries.size > 1 -> throw IllegalStateException("""
        |
        |Only one AccessAnnotation is permitted on a WebAction. Remove one from ${action.name}::${action.function.name}():
        | ${actionEntries.map { it.annotation }}
        |
        """.trimMargin())
        // This action is explicitly marked as unauthenticated.
        actionEntries.size == 1 && action.hasAnnotation<Unauthenticated>() -> return null
        // Successfully return @Authenticated or custom Access Annotation
        actionEntries.size == 1 -> return AccessInterceptor(actionEntries[0].services.toSet(), actionEntries[0].roles.toSet(), caller)
        // Not exactly one access annotation. Fail with a useful message.
        else -> {
          val requiredAnnotations = mutableListOf<KClass<out Annotation>>()
          requiredAnnotations += Authenticated::class
          requiredAnnotations += Unauthenticated::class
          requiredAnnotations += registeredEntries.map { it.annotation }
          throw IllegalStateException("""AccessInterceptor A) can't find an AccessAnnotation for this WebAction or B) can't find the related AccessAnnotationEntry multibinding.
          |
          |A) ${action.name}::${action.function.name}() must be annotated with one of the following AccessAnnotations that have a matching AccessAnnotationEntry:
          |   $requiredAnnotations
          |
          |B) Add an AccessAnnotationEntry multibinding in a module for one of the annotations on ${action.name}::${action.function.name}():
          |   ${action.function.annotations}
          |
          |   AccessAnnotationEntry Example Multibinding:
          |   multibind<AccessAnnotationEntry>().toInstance(
          |     AccessAnnotationEntry<${action.function.annotations.filter { it.annotationClass.simpleName.toString().endsWith("Access") }.firstOrNull()?.annotationClass?.simpleName ?: "{Access Annotation Class Simple Name}"}>(roles = ???, services = ???))
          |
          """.trimMargin())
        }
      }
    }

    private fun Authenticated.toAccessAnnotationEntry() = AccessAnnotationEntry(
        Authenticated::class, services.toList(), roles.toList())

    private fun Unauthenticated.toAccessAnnotationEntry() = AccessAnnotationEntry(
        Unauthenticated::class, listOf(), listOf())

    private inline fun <reified T : Annotation> Action.hasAnnotation() =
        function.annotations.any { it.annotationClass == T::class }
  }
}

