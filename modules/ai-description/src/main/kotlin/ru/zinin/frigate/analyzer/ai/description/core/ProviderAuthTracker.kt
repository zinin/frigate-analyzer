package ru.zinin.frigate.analyzer.ai.description.core

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionException
import ru.zinin.frigate.analyzer.ai.description.api.DescriptionProviderAuthEvent
import ru.zinin.frigate.analyzer.ai.description.api.ProviderAuthStates
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * Авторизация принадлежит области учётных данных, а не пресету и не провайдеру: два grok-пресета на
 * одной модели делят один `auth.json`, и отказ обязан дать одно событие на двоих, а BYOK-модель
 * ходит по собственному ключу — её успех не значит, что сессия xAI жива. Ключ приходит от backend-а
 * ([DescriptionBackend.authScopeId]), который получает его от своей фабрики.
 *
 * Переход и публикация идут под одним замком области: слушатель доставляет события владельцу в
 * порядке публикации, и разъехавшийся порядок оставил бы его с сообщением об отказе при рабочих
 * учётных данных. Замок именно на область, а не общий: медленный слушатель одной области не должен
 * задерживать событие другой.
 *
 * Стартовое значение — `UNKNOWN`; для переходов оно ведёт себя как `HEALTHY` (первый `Unauthorized`
 * публикует LOST, первый успех события не даёт), отличается только тем, что рисуется в `/ai`.
 */
class ProviderAuthTracker(
    private val eventPublisher: ApplicationEventPublisher,
) : ProviderAuthStates {
    private val states = ConcurrentHashMap<String, AtomicReference<ProviderAuthStates.Health>>()
    private val locks = ConcurrentHashMap<String, Any>()

    override fun byScope(): Map<String, ProviderAuthStates.Health> = states.mapValues { it.value.get() }

    fun onUnauthorized(
        authScopeId: String,
        e: DescriptionException.Unauthorized,
        recoveryHint: String,
    ) {
        synchronized(lockFor(authScopeId)) {
            val state = stateFor(authScopeId)
            val previous = state.get()
            if (previous == ProviderAuthStates.Health.LOST) return
            state.set(ProviderAuthStates.Health.LOST)
            logger.error(e) {
                "Description credentials '$authScopeId' were rejected; descriptions stay unavailable " +
                    "until re-login. Fix: $recoveryHint"
            }
            publish(
                DescriptionProviderAuthEvent(
                    authScopeId = authScopeId,
                    state = DescriptionProviderAuthEvent.State.LOST,
                    detail = e.detail,
                    recoveryHint = recoveryHint,
                ),
                state,
                previous,
            )
        }
    }

    fun onSuccess(
        authScopeId: String,
        recoveryHint: String,
    ) {
        synchronized(lockFor(authScopeId)) {
            val state = stateFor(authScopeId)
            val previous = state.get()
            state.set(ProviderAuthStates.Health.HEALTHY)
            if (previous != ProviderAuthStates.Health.LOST) return
            logger.info { "Description credentials '$authScopeId' work again" }
            publish(
                DescriptionProviderAuthEvent(
                    authScopeId = authScopeId,
                    state = DescriptionProviderAuthEvent.State.RESTORED,
                    detail = null,
                    recoveryHint = recoveryHint,
                ),
                state,
                previous,
            )
        }
    }

    /**
     * Spring доставляет событие синхронно, на этом же потоке. Слушатель, который бросил, не должен
     * съесть переход: без отката такой же отказ больше никогда не поднял бы событие, и владелец не
     * узнал бы о нём вовсе. Откат простым `set` безопасен — писать состояние области может только
     * держатель её замка.
     */
    private fun publish(
        event: DescriptionProviderAuthEvent,
        state: AtomicReference<ProviderAuthStates.Health>,
        previous: ProviderAuthStates.Health,
    ) {
        try {
            eventPublisher.publishEvent(event)
        } catch (e: Exception) {
            state.set(previous)
            logger.warn(e) {
                "Cannot publish ${event.state} auth event for '${event.authScopeId}'; " +
                    "the transition will be reported again on the next occurrence"
            }
        }
    }

    private fun stateFor(authScopeId: String) = states.computeIfAbsent(authScopeId) { AtomicReference(ProviderAuthStates.Health.UNKNOWN) }

    private fun lockFor(authScopeId: String) = locks.computeIfAbsent(authScopeId) { Any() }
}
