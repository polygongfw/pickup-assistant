package com.coldbrew.cainiao.data

/**
 * MTOP 客户端需要的会话能力抽象。
 *
 * 之所以抽成接口而不是直接用 [SessionStore]：让网关协议层（签名、token 引导、Set-Cookie 处理）
 * 完全不依赖 Android，这样可以用普通 JVM 单元测试真打一次接口来验证签名正确性
 * —— 见 `src/test/java/.../MtopLiveTest.kt`。
 */
interface CookieStore {
    /** 请求头用的 cookie 串。 */
    fun cookieHeader(): String

    /** `_m_h5_tk` 下划线前的 token 段；没有则返回空串，首包会走 TOKEN_EMPTY 引导流程。 */
    fun token(): String

    fun put(c: Ck)

    fun remove(name: String)

    /** 调用成功后落盘（网关会刷新 _m_h5_tk）。 */
    fun persist()
}

/** 内存实现，仅用于测试。 */
class MemoryCookieStore(initial: Map<String, String> = emptyMap()) : CookieStore {

    private val jar = LinkedHashMap<String, String>()

    init {
        jar.putAll(initial)
    }

    override fun cookieHeader(): String =
        jar.entries.filter { it.value.isNotEmpty() }
            .joinToString("; ") { "${it.key}=${it.value}" }

    override fun token(): String = (jar["_m_h5_tk"] ?: "").substringBefore('_')

    override fun put(c: Ck) {
        jar[c.name] = c.value
    }

    override fun remove(name: String) {
        jar.remove(name)
    }

    override fun persist() = Unit
}
