package io.ghostsoftware.ghostchat

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * AppSessionManager.kt
 *
 * ЕДИНАЯ ТОЧКА ПРАВДЫ для жизненного цикла Firebase-листенеров и для
 * упорядоченного logout. Раньше этого файла не было — отсюда два бага:
 *
 *  1. ChatListScreen вешал addChildEventListener на "messages" в
 *     LaunchedEffect БЕЗ onDispose/removeEventListener. Листенер жил
 *     вечно, включая после signOut() — и после инвалидации токена
 *     продолжал дёргаться на любое изменение в узле, получая
 *     PERMISSION_DENIED от security rules. Отсюда спам в logcat.
 *
 *  2. Room-таблица ratchet_sessions (rootKey/chainKey/seqNum) переживала
 *     signOut() → relogin. GhostSessionManager брал СТАРУЮ сессию из
 *     Room и пытался шифровать/дешифровать ею дальше, хотя собеседник
 *     уже продвинул свой рачет (или наш identity-ключ пересоздался) →
 *     AEADBadTagException.
 *
 * ПРАВИЛО: основной путь отписки — DisposableEffect.onDispose { untrack(...) }
 * в самом композабле. detachAllListeners() ниже — ТОЛЬКО страховка на
 * случай, если Compose не успел корректно продиспоузить (kill процесса,
 * race при быстром logout) — вызывается ровно один раз, перед signOut().
 */
object AppSessionManager {

    private const val TAG = "AppSessionManager"

    private data class TrackedListener(
        val query: Query,
        val valueListener: ValueEventListener? = null,
        val childListener: ChildEventListener? = null
    )

    // Ключ и значение совпадают — нужен только потокобезопасный Set.
    private val tracked = ConcurrentHashMap<TrackedListener, Boolean>()

    /** Регистрирует ValueEventListener в реестре. Вызывать ДО addValueEventListener. */
    fun track(query: Query, listener: ValueEventListener): ValueEventListener {
        tracked[TrackedListener(query, valueListener = listener)] = true
        return listener
    }

    /** Регистрирует ChildEventListener в реестре. Вызывать ДО addChildEventListener. */
    fun track(query: Query, listener: ChildEventListener): ChildEventListener {
        tracked[TrackedListener(query, childListener = listener)] = true
        return listener
    }

    /**
     * Снимает КОНКРЕТНЫЙ листенер и убирает его из реестра.
     * Это ОСНОВНОЙ путь очистки — вызывать из onDispose{} композаблов.
     */
    fun untrack(query: Query, listener: ValueEventListener) {
        query.removeEventListener(listener)
        tracked.keys.removeAll { it.query == query && it.valueListener == listener }
    }

    fun untrack(query: Query, listener: ChildEventListener) {
        query.removeEventListener(listener)
        tracked.keys.removeAll { it.query == query && it.childListener == listener }
    }

    /**
     * Страховочный проход: силой снимает АБСОЛЮТНО ВСЕ листенеры, которые
     * ещё числятся в реестре (значит их onDispose по какой-то причине не
     * отработал). Вызывается ТОЛЬКО из performLogout(), ДО signOut() —
     * снимать листенеры ПОСЛЕ signOut() уже бессмысленно, PERMISSION_DENIED
     * к этому моменту уже прилетит на следующем срабатывании.
     */
    private fun detachAllListeners() {
        if (tracked.isEmpty()) {
            Log.d(TAG, "detachAllListeners: nothing to detach")
            return
        }
        Log.w(TAG, "detachAllListeners: force-detaching ${tracked.size} leftover listener(s)")
        tracked.keys.forEach { t ->
            try {
                t.valueListener?.let { t.query.removeEventListener(it) }
                t.childListener?.let { t.query.removeEventListener(it) }
            } catch (e: Exception) {
                Log.e(TAG, "detachAllListeners: failed to remove listener: ${e.javaClass.simpleName}")
            }
        }
        tracked.clear()
    }

    /**
     * ПОЛНЫЙ, СТРОГО УПОРЯДОЧЕННЫЙ выход из аккаунта.
     *
     * Порядок критичен и НЕ должен меняться:
     *   1. Снимаем все Firebase-листенеры (пока сессия ещё валидна).
     *   2. Чистим ratchet_sessions в Room — это ОДНОРАЗОВОЕ протокольное
     *      состояние конкретной переписки (root/chain ключи, seqNum),
     *      НЕ идентичность. Identity-ключи (X25519/Ed25519 в
     *      GhostKeyManager) здесь НЕ трогаем — иначе относигеринг
     *      расшифровку истории при повторном входе тем же аккаунтом
     *      станет невозможной. При следующей отправке/получении
     *      GhostSessionManager сам поднимет свежую X3DH-сессию.
     *   3. Presence "offline" — best-effort, пока токен ещё жив.
     *   4. FirebaseAuth.signOut().
     *   5. goOffline()/goOnline() — сбрасываем connection pool SDK,
     *      чтобы следующий логин не тащил закэшированные подписки.
     *
     * Вызывать ИЗ MainActivity (GhostChatApp) ПЕРЕД сменой экрана на "auth".
     */
    suspend fun performLogout(database: AppDatabase) = withContext(Dispatchers.IO) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid

        // 1. Листенеры — первым делом, пока сессия ещё валидна.
        detachAllListeners()

        // 2. Протокольное (не идентификационное!) состояние — обнуляем полностью.
        try {
            database.ratchetSessionDao().deleteAllSessions()
            Log.i(TAG, "performLogout: ratchet_sessions cleared")
        } catch (e: Exception) {
            Log.e(TAG, "performLogout: failed to clear ratchet sessions: ${e.javaClass.simpleName}")
        }

        // 3. Presence best-effort.
        try {
            if (uid != null) {
                FirebaseDatabase.getInstance().getReference("users")
                    .child(uid).child("status")
                    .setValue(System.currentTimeMillis())
            }
        } catch (e: Exception) {
            Log.w(TAG, "performLogout: presence update failed: ${e.javaClass.simpleName}")
        }

        // 4. Собственно выход из Firebase-аккаунта.
        FirebaseAuth.getInstance().signOut()

        // 5. Сброс connection pool SDK — чистый старт для следующего логина.
        try {
            FirebaseDatabase.getInstance().goOffline()
            FirebaseDatabase.getInstance().goOnline()
        } catch (e: Exception) {
            Log.w(TAG, "performLogout: goOffline/goOnline failed: ${e.javaClass.simpleName}")
        }

        Log.i(TAG, "performLogout: complete")
    }
}
