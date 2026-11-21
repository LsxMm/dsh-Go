// Run with the repository's unchanged PhoneToolCatalog.kt and Json.kt.
import io.github.social.dsh_app.phone.PhoneToolCatalog
import io.github.social.dsh_app.phone.Json
fun main() { print(Json.write(PhoneToolCatalog.listJson())) }
