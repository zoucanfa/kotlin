// WITH_RUNTIME

@file:JvmName("TestKt")
package test

import kotlinx.android.parcel.*
import android.os.Parcel
import android.os.Parcelable

@MagicParcel
data class User(val firstName: String, val secondName: String, val age: Int) : Parcelable

fun box() = parcelTest { parcel ->
    val user = User("John", "Smith", 20)
    user.writeToParcel(parcel, 0)

    val bytes = parcel.marshall()
    System.err.println(bytes)
    parcel.unmarshall(bytes, 0, bytes.size)

    val user2 = readFromParcel<User>(parcel)
    assert(user == user2)
}

private fun parcelTest(block: (Parcel) -> Unit): String {
    val parcel = Parcel.obtain()
    try {
        block(parcel)
        return "OK"
    } finally {
        parcel.recycle()
    }
}

inline fun <reified T> readFromParcel(parcel: Parcel): T {
    val creator = T::class.java.getDeclaredField("CREATOR").get(null)
    return creator::class.java.getDeclaredMethod("createFromParcel", Parcel::class.java).invoke(creator, parcel) as T
}