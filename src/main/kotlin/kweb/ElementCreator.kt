package kweb

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kweb.html.BodyElement
import kweb.html.HeadElement
import kweb.plugins.KwebPlugin
import kweb.state.KVal
import kweb.util.KWebDSL
import kweb.util.json
import mu.KLogging
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.reflect.KClass

typealias Cleaner = () -> Unit

/**
 * Responsible for creating new DOM elements, and cleaning up [Cleaner]s, [KVar]s, and other
 * related objects when DOM elements are deleted.
 *
 * [ElementCreator] is typically used as a [receiver](https://stackoverflow.com/a/45875492)
 * for element creation functions like [p] or [element].
 */
@KWebDSL
open class ElementCreator<out PARENT_TYPE : Element>(
    val parent: PARENT_TYPE,
    val parentCreator: ElementCreator<*>? = parent.creator,
    val namespace : String? = parentCreator?.namespace,
    val position: Int? = null
) {

    companion object : KLogging()

    private val cleanupListeners: MutableCollection<Cleaner> = ConcurrentLinkedQueue<Cleaner>()

    @Volatile
    private var isCleanedUp = false

    val elementsCreatedCount: Int get() = elementsCreated.size

    internal
    val elementsCreated = ConcurrentLinkedQueue<Element>()

    val browser: WebBrowser get() = parent.browser

    /**
     * Create a new element, specifying its [tag](https://www.javatpoint.com/html-tags) and
     * [attributes](https://www.javatpoint.com/html-attributes).
     *
     * Tag-specific functions like [p], [select], and others call this function and should
     * be used in preference to it if available.
     */
    fun element(tag: String, attributes: Map<String, JsonPrimitive> = attr): Element {

        val mutAttributes = HashMap(attributes)

        if (position != null && elementsCreatedCount == 2) {
            logger.warn {
                """
                It's unwise to create multiple elements using the same ElementCreator when position is specified,
                because each element will be added at the same position among its siblings, which will result in them
                being inserted in reverse-order.
                """.trimIndent().trim()
            }
        }

        val id: String = mutAttributes.computeIfAbsent("id") { JsonPrimitive("K" + browser.generateId()) }.content
        val htmlDoc = browser.htmlDocument.get()
        val createElementStatement = when(namespace) {
            null -> "document.createElement(tag);"
            else -> "document.createElementNS(\"${namespace}\", tag);"
        }
        when {
            parent.browser.isCatchingOutbound() != null -> {
                //language=JavaScript
                val createElementJs = """
                    let tag = {};
                    let attributes = {};
                    let myId = {};
                    let parentId = {};
                    let position = {};
                    let newEl = $createElementStatement
                    newEl.setAttribute("id", myId);
                    for (const key in attributes) {
                        if ( key !== "id") {
                            newEl.setAttribute(key, attributes[key]);
                        }
                    }
                    let parentElement = document.getElementById(parentId);
                    
                    if (position > -1) {
                        parentElement.insertBefore(newEl, parentElement.children[position]);
                    } else {
                        parentElement.appendChild(newEl);
                    }
                """.trimIndent()
                browser.callJsFunction(createElementJs, JsonPrimitive(tag), JsonObject(mutAttributes), id.json,
                        JsonPrimitive(parent.id), JsonPrimitive(position ?: -1))
            }
            htmlDoc != null -> {
                val jsElement = when (parent) {
                    is HeadElement -> {
                        htmlDoc.head().appendElement(tag)
                    }
                    is BodyElement -> {
                        htmlDoc.body().appendElement(tag)
                    }
                    else -> htmlDoc.getElementById(parent.id).appendElement(tag)
                }!!
                for ((k, v) in mutAttributes) {
                    jsElement.attr(k, v.content)
                }
            }
            else -> {
                //The way I have written this function, instead of attributes.get(), we now use attributes[].
                //language=JavaScript
                val createElementJs = """
                    let tag = {};
                    let attributes = {};
                    let myId = {};
                    let parentId = {};
                    let position = {};
                    let newEl = document.createElement(tag);
                    if (attributes["id"] === undefined) {
                        newEl.setAttribute("id", myId);
                    }
                    for (const key in attributes) {
                            newEl.setAttribute(key, attributes[key]);
                    }
                    let parentElement = document.getElementById(parentId);
                    
                    if (position == null) {
                        parentElement.appendChild(newEl);
                    } else {
                        parentElement.insertBefore(newEl, parentElement.children[position]);
                    }
                """.trimIndent()
                parent.callJsFunction(createElementJs, tag.json, JsonObject(mutAttributes), id.json,
                        parent.id.json, (position ?: -1).json)
            }
        }
        val newElement = Element(parent.browser, this, tag = tag, id = id)
        elementsCreated += newElement
        for (plugin in parent.browser.kweb.plugins) {
            plugin.elementCreationHook(newElement)
        }
        onCleanup(withParent = false) {
            logger.debug { "Deleting element ${newElement.id}" }
            newElement.deleteIfExists()
        }
        return newElement
    }

    /**
     * Specify that a specific plugin be provided in [Kweb.plugins], throws an exception if not.
     */
    fun require(vararg plugins: KClass<out KwebPlugin>) = parent.browser.require(*plugins)

    /**
     * Specify a listener to be called when this element is removed from the DOM.
     *
     * @param withParent If `true` this cleaner will be called if this element is cleaned up, or if
     *                   any ancestor element of this ElementCreator is cleaned up.  Otherwise it will
     *                   only be cleaned up if this ElementCreator is cleaned up specifically.
     *
     *                   As a rule-of-thumb, use 'true' for anything except deleting DOM elements.
     */
    fun onCleanup(withParent: Boolean, f: Cleaner) {
        if (withParent) {
            parentCreator?.onCleanup(true, f)
        }
        cleanupListeners += f
    }

    fun cleanup() {
        // TODO: Warn if called twice?
        if (!isCleanedUp) {
            isCleanedUp = true
            cleanupListeners.forEach { it() }
        }
    }

    fun text(text: String) {
        this.parent.text(text)
    }

    fun text(text: KVal<String>) {
        this.parent.text(text)
    }
}