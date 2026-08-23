package alaphant.agent

import org.spongepowered.asm.service.IGlobalPropertyService
import org.spongepowered.asm.service.IPropertyKey
import java.util.concurrent.ConcurrentHashMap

class AlaphantGlobalPropertyService : IGlobalPropertyService {
    private val keys = ConcurrentHashMap<String, IPropertyKey>()
    private val properties = ConcurrentHashMap<IPropertyKey, Any>()

    override fun resolveKey(name: String): IPropertyKey = keys.computeIfAbsent(name, ::Key)

    @Suppress("UNCHECKED_CAST")
    override fun <T> getProperty(key: IPropertyKey): T? = properties[key] as T?

    override fun setProperty(key: IPropertyKey, value: Any?) {
        if (value == null) properties.remove(key) else properties[key] = value
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> getProperty(key: IPropertyKey, defaultValue: T): T =
        properties[key] as T? ?: defaultValue

    override fun getPropertyString(key: IPropertyKey, defaultValue: String?): String? =
        properties[key]?.toString() ?: defaultValue

    private data class Key(private val name: String) : IPropertyKey {
        override fun toString(): String = name
    }
}
