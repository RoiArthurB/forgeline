package fr.arthurbrugiere.forgeline.core.forge

/** Compares runs of digits as numbers, so v1.10 comes after v1.9. */
object VersionOrder : Comparator<String> {
    private val chunks = Regex("""\d+|\D+""")

    override fun compare(a: String, b: String): Int {
        val left = chunks.findAll(a).map { it.value }.toList()
        val right = chunks.findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(left.size, right.size)) {
            val x = left[i]
            val y = right[i]
            val order = if (x[0].isDigit() && y[0].isDigit()) {
                x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 } ?: x.trimStart('0').compareTo(y.trimStart('0'))
            } else {
                x.compareTo(y, ignoreCase = true)
            }
            if (order != 0) return order
        }
        return left.size.compareTo(right.size)
    }
}
