// EXPECTED_REACHABLE_NODES: 489

fun box(): String {
    var x: String? = foo()
    var r = x + bar()
    if (r != "foobar") return "fail1: $r"

    x = null
    r = x + bar()
    if (r != "nullbar") return "fail2: $r"

    x = foo()
    r = x + null
    if (r != "foonull") return "fail3: $r"

    r = foo()
    r += bar()
    if (r != "foobar") return "fail4: $r"

    x = null
    r = x + null
    if (r != "nullnull") return "fail5: $r"

    return "OK"
}

fun foo() = "foo"

fun bar() = "bar"