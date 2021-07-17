package kweb.state

import kotlinx.serialization.json.JsonPrimitive
import kweb.*
import kweb.client.FunctionCall
import kweb.client.Server2ClientMessage
import kweb.shoebox.KeyValue
import kweb.shoebox.OrderedViewSet
import kweb.shoebox.Shoebox
import kweb.state.RenderState.*
import mu.KotlinLogging
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Created by ian on 6/18/17.
 */

private val logger = KotlinLogging.logger {}

fun <T : Any?> ElementCreator<*>.render(
    value: KVal<T>,
    container: ElementCreator<*>.() -> Element
        = { span().setAttribute("style", JsonPrimitive("display: contents;")) },
    block: ElementCreator<Element>.(T) -> Unit
) {

    // NOTE: Per https://github.com/kwebio/kweb-core/issues/151 eventually render() won't rely on a container element.
    val containerElement: Element = container(this)

    val previousElementCreator: AtomicReference<ElementCreator<Element>?> = AtomicReference(null)

    val renderState = AtomicReference(NOT_RENDERING)

    fun eraseAndRender() {
        do {
            if (parent.browser.isCatchingOutbound() == null) {
                parent.browser.batch(WebBrowser.CatcherType.RENDER) {
                    containerElement.removeChildren() // REMOVE ALL ELEMENTS BETWEEN startSpan and endSpan
                    containerElement.new {
                        previousElementCreator.getAndSet(this)?.cleanup()
                        renderState.set(RENDERING_NO_PENDING_CHANGE)
                        block(value.value)
                        if (renderState.get() == RENDERING_NO_PENDING_CHANGE) {
                            renderState.set(NOT_RENDERING)
                        }
                    }
                }
            } else {
                containerElement.removeChildren()
                containerElement.new {
                    previousElementCreator.getAndSet(this)?.cleanup()
                    renderState.set(RENDERING_NO_PENDING_CHANGE)
                    block(value.value)
                    if (renderState.get() == RENDERING_NO_PENDING_CHANGE) {
                        renderState.set(NOT_RENDERING)
                    }
                }
            }
        } while (renderState.get() != NOT_RENDERING)
    }

    val listenerHandle = value.addListener { _, _ ->
        when (renderState.get()) {
            NOT_RENDERING -> {
                eraseAndRender()
            }
            RENDERING_NO_PENDING_CHANGE -> {
                renderState.set(RENDERING_WITH_PENDING_CHANGE)
            }
            else -> {
                // This space intentionally left blank
            }
        }
    }

    containerElement.new {
        previousElementCreator.getAndSet(this)?.cleanup()
        renderState.set(RENDERING_NO_PENDING_CHANGE)
        block(value.value)
        if (renderState.get() == RENDERING_WITH_PENDING_CHANGE) {
            eraseAndRender()
        } else {
            renderState.set(NOT_RENDERING)
        }

    }

    this.onCleanup(false) {
        containerElement.deleteIfExists()
    }

    this.onCleanup(true) {
        previousElementCreator.getAndSet(null)?.cleanup()
        value.removeListener(listenerHandle)
    }
}

fun ElementCreator<*>.closeOnElementCreatorCleanup(kv: KVal<*>) {
    this.onCleanup(withParent = true) {
        kv.close(CloseReason("Closed because a parent ElementCreator was cleaned up"))
    }
}

fun <T : Any> ElementCreator<*>.toVar(shoebox: Shoebox<T>, key: String): KVar<T> {
    val value = shoebox[key] ?: throw NoSuchElementException("Key $key not found")
    val w = KVar(value)
    w.addListener { _, n ->
        require(this.browser.isCatchingOutbound() != WebBrowser.CatcherType.IMMEDIATE_EVENT) {
            """You appear to be modifying Shoebox state from within an onImmediate callback, which
                |should only make simple modifications to the DOM.""".trimMargin()
        }
        shoebox[key] = n
    }
    val changeHandle = shoebox.onChange(key) { _, n, _ -> w.value = n }
    w.onClose { shoebox.deleteChangeListener(key, changeHandle) }
    this.onCleanup(withParent = true) {
        w.close(CloseReason("Closed because parent ElementCreator was cleaned up"))
    }
    return w
}

private data class ItemInfo<ITEM : Any>(val creator: ElementCreator<Element>, val KVar: KVar<ITEM>)

data class IndexedItem<I>(val index: Int, val total: Int, val item: I)

/**
 *
 *
 * @sample ordered_view_set_sample
 */
fun <ITEM : Any, EL : Element> ElementCreator<EL>.renderEach(orderedViewSet: OrderedViewSet<ITEM>, renderer: ElementCreator<EL>.(KVar<ITEM>) -> Unit) {
    val items = CopyOnWriteArrayList<ItemInfo<ITEM>>()
    for (keyValue in orderedViewSet.keyValueEntries) {
        items += createItem(orderedViewSet, keyValue, renderer, insertAtPosition = null)
    }

    val onInsertHandler = orderedViewSet.onInsert { index, inserted ->
        if (index < items.size) {
            items.add(index, createItem(orderedViewSet, inserted, renderer, index))
        } else {
            items.add(createItem(orderedViewSet, inserted, renderer, insertAtPosition = null))
        }
    }
    this.onCleanup(true) { orderedViewSet.deleteInsertListener(onInsertHandler) }

    val onRemoveHandler = orderedViewSet.onRemove { index, keyValue ->
        if (index >= items.size) {
            logger.warn("Invalid index $index to retrieve item from items list of size ${items.size} for key ${keyValue.key} and item ${keyValue.value}, ignoring.")
        } else {
            val removed = items.removeAt(index)
            removed.creator.cleanup()
            removed.KVar.close(CloseReason("Closed because associated item was removed from OrderedViewSet"))
        }
    }

    this.onCleanup(true) {

        orderedViewSet.deleteRemoveListener(onRemoveHandler)
    }
}

private fun <ITEM : Any, EL : Element> ElementCreator<EL>.createItem(
    orderedViewSet: OrderedViewSet<ITEM>,
    keyValue: KeyValue<ITEM>,
    renderer: ElementCreator<EL>.(KVar<ITEM>) -> Unit,
    insertAtPosition: Int?
)
        : ItemInfo<ITEM> {
    val itemElementCreator = ElementCreator(this.parent, this, position = insertAtPosition)
    val itemVar = itemElementCreator.toVar(orderedViewSet.view.viewOf, keyValue.key)
    try {
        renderer(itemElementCreator, itemVar)
    } catch (e: Exception) {
        logger.error("Error rendering item", e)
    }

    if (itemElementCreator.elementsCreatedCount > 1) {
        /*
         * Only one element may be created per-item because otherwise it would be much more complicated to figure
         * out where new items should be inserted by the onInsert handler below.  onRemove would be easier because
         * we could just call itemElementCreator.cleanup() to delete that item's elements.
         *
         * This shouldn't be an onerous requirement because typically with lists of things there is just one
          * root <ol> or <ul> per item.  If it does turn out to be a problem we'll need to find another approach.
         */
        error(
            """
            Only one element may be created per item but ${itemElementCreator.elementsCreated} were created for
            item key ${keyValue.key}.  Note that this element may have as many children as you like, so you may just need
            to wrap the elements in a <DIV> or other element type.
""".trimIndent()
        )
    }
    return ItemInfo(itemElementCreator, itemVar)
}

private enum class RenderState {
    NOT_RENDERING, RENDERING_NO_PENDING_CHANGE, RENDERING_WITH_PENDING_CHANGE
}

fun ordered_view_set_sample() {
    data class Cat(val name: String, val color: String)

    val cats = Shoebox<Cat>()
    val catColorView = cats.view("catColors", Cat::color)
    Kweb(port = 1234, buildPage = {
        doc.body.new {
            renderEach(catColorView.orderedSet("brown")) { brownCat ->
                div().new {
                    h1().text(brownCat.map(Cat::name))
                }
            }
        }
    })
}

