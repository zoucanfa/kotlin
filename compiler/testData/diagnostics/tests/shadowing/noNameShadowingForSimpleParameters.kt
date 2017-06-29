// !DIAGNOSTICS: -UNUSED_PARAMETER

open class Base {
    open fun foo(name: String) {}
}

fun test1(name: String) {
    class Local : Base() {
        override fun foo(name: String) {
        }
    }
}

fun test2(param: String) {
    fun local(param: String) {}
}