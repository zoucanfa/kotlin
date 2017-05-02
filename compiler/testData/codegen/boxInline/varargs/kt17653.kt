// FILE: 1.kt

package test

inline fun panel(vararg constraints: String, init: String.() -> String): String {
    return "O".init()
}

// FILE: 2.kt

import test.*

fun box(): String {
    return panel {
        this + "K"
    }
}

