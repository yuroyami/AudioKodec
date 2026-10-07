package io.github.yuroyami.audiokodec.internal.tag

/**
 * Membership index for untrusted tag values and identities.
 *
 * An AVL tree bounds lookup and insertion comparisons to O(log n), including
 * strings with deliberately colliding hashes. Result order belongs to the
 * caller's list. There is no deletion or traversal to maintain here.
 */
internal class TagValueIndex<T>(
    private val comparator: Comparator<T>,
    private val beforeInsert: ((Int) -> Unit)? = null,
) {
    private class Node<T>(val value: T) {
        var left: Node<T>? = null
        var right: Node<T>? = null
        var height: Int = 1
    }

    private var root: Node<T>? = null
    var size: Int = 0
        private set

    operator fun contains(value: T): Boolean {
        var node = root
        while (node != null) {
            val compared = comparator.compare(value, node.value)
            node = when {
                compared < 0 -> node.left
                compared > 0 -> node.right
                else -> return true
            }
        }
        return false
    }

    fun add(value: T): Boolean {
        val before = size
        root = insert(root, value)
        return size != before
    }

    private fun insert(node: Node<T>?, value: T): Node<T> {
        if (node == null) {
            beforeInsert?.invoke(size + 1)
            size++
            return Node(value)
        }
        val compared = comparator.compare(value, node.value)
        when {
            compared < 0 -> node.left = insert(node.left, value)
            compared > 0 -> node.right = insert(node.right, value)
            else -> return node
        }
        updateHeight(node)
        val balance = height(node.left) - height(node.right)
        if (balance > 1) {
            val left = node.left!!
            if (height(left.left) < height(left.right)) node.left = rotateLeft(left)
            return rotateRight(node)
        }
        if (balance < -1) {
            val right = node.right!!
            if (height(right.right) < height(right.left)) node.right = rotateRight(right)
            return rotateLeft(node)
        }
        return node
    }

    private fun height(node: Node<T>?): Int = node?.height ?: 0

    private fun updateHeight(node: Node<T>) {
        node.height = 1 + maxOf(height(node.left), height(node.right))
    }

    private fun rotateLeft(node: Node<T>): Node<T> {
        val right = node.right!!
        node.right = right.left
        right.left = node
        updateHeight(node)
        updateHeight(right)
        return right
    }

    private fun rotateRight(node: Node<T>): Node<T> {
        val left = node.left!!
        node.left = left.right
        left.right = node
        updateHeight(node)
        updateHeight(left)
        return left
    }
}
