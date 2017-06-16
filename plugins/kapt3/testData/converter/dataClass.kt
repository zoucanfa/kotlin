import java.util.Date

data class User(val firstName: String, val secondName: String, val age: Int) {
    fun procedure() {}
}

annotation class PrimaryKey

data class Task (@field:PrimaryKey val uuid: String, val text: String, val updatedAt: Date)