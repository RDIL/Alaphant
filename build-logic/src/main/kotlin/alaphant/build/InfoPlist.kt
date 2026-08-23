package alaphant.build

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Minimal reader for the JVM configuration in `Charles.app/Contents/Info.plist`.
 */
internal object InfoPlist {
    data class Config(
        val jvmOptions: List<String>,
        val mainModuleAndClass: String?,
        val version: String?,
    )

    fun read(plist: File, appRoot: File): Config {
        if (!plist.isFile) return Config(emptyList(), null, null)

        val factory = DocumentBuilderFactory.newInstance().apply {
            // These are local, trusted files, but there is no reason to resolve anything external.
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            isExpandEntityReferences = false
        }
        val root = factory.newDocumentBuilder().parse(plist).documentElement
        val dict = root.childElements().firstOrNull { it.tagName == "dict" }
            ?: return Config(emptyList(), null, null)

        val entries = dict.childElements()
        val values = HashMap<String, Element>()
        var pendingKey: String? = null
        for (element in entries) {
            if (element.tagName == "key") {
                pendingKey = element.textContent.trim()
            } else {
                pendingKey?.let { values[it] = element }
                pendingKey = null
            }
        }

        val options = values["JVMOptions"]
            ?.childElements()
            ?.filter { it.tagName == "string" }
            ?.map { it.textContent.replace(APP_ROOT, appRoot.absolutePath) }
            ?: emptyList()

        return Config(
            jvmOptions = options,
            mainModuleAndClass = values["JVMMainClassName"]?.textContent?.trim(),
            version = values["CFBundleShortVersionString"]?.textContent?.trim(),
        )
    }

    private const val APP_ROOT = $$"$APP_ROOT"

    private fun Element.childElements(): List<Element> =
        (0 until childNodes.length)
            .map { childNodes.item(it) }
            .filter { it.nodeType == Node.ELEMENT_NODE }
            .map { it as Element }
}
