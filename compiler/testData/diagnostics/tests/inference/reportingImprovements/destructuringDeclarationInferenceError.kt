// !DIAGNOSTICS: -UNUSED_VARIABLE

class X
operator fun <T> X.component1(): T = TODO()

class Y<T>
operator fun <T> Y<T>.component1(): T = TODO()

fun test() {
    val (a) = <!DESTRUCTURING_DECLARATION_INFERENCE_ERROR!>X()<!>
    val (<!TYPE_INFERENCE_EXPECTED_TYPE_MISMATCH!>b: Int<!>) = Y<String>()
}
