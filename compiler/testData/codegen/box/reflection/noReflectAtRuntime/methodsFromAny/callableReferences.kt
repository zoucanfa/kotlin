// TODO: muted automatically, investigate should it be ran for JS or not
// IGNORE_BACKEND: JS
fun foo() {

}

fun box(): String {
    ::foo
//    checkEquals(M::bar, M::bar)
//    checkEquals(::M, ::M)
//
//    checkEquals(::topLevelFun, ::topLevelFun)
//    checkEquals(::topLevelProp, ::topLevelProp)
//
//    checkToString(M::foo, "function foo")
//    checkToString(M::bar, "property bar")
//    checkToString(::M, "constructor")
//
//    checkToString(::topLevelFun, "function topLevelFun")
//    checkToString(::topLevelProp, "property topLevelProp")

    return "OK"
}
