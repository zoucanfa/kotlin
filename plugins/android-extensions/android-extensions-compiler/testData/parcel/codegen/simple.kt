// CURIOUS_ABOUT writeToParcel

import kotlinx.android.parcel.*

@MagicParcel
class User(val firstName: String, val lastName: String, val age: Int, val isProUser: Boolean)