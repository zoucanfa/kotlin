// !DIAGNOSTICS: -UNUSED_PARAMETER
// FILE: a/a.java
package a;

public class a {}

// FILE: a/b.java
package a;

public class b {
    public void a_b() {}
}

// FILE: test/a.java
package test;

public class a {}

// FILE: b.kt
package test

val x = a.<!UNRESOLVED_REFERENCE!>b<!>()

// FILE: test/c.java
package test;

import a.a;

public class c {
    public static a getA() { return null; }
}

// FILE: c.kt
package test

fun foo() {
    val a = c.getA()
    a.<!UNRESOLVED_REFERENCE!>a<!>
    a.<!UNRESOLVED_REFERENCE!>a<!>()
}
