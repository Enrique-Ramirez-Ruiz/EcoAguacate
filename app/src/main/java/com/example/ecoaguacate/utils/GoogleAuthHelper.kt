
import android.os.Build
import androidx.annotation.RequiresApi
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import okhttp3.*
import org.json.JSONObject
import java.security.interfaces.RSAPrivateKey
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.*
import java.util.Base64

object GoogleAuthHelper {
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val SCOPE = "https://www.googleapis.com/auth/cloud-platform"

    @RequiresApi(Build.VERSION_CODES.O)
    fun getAccessToken(serviceAccountJson: JSONObject): String {
        val clientEmail = serviceAccountJson.getString("client_email")
        val privateKeyPem = serviceAccountJson.getString("private_key")

        // Convertir la clave privada PEM a RSAPrivateKey
        val privateKeyClean = privateKeyPem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s+".toRegex(), "")
        val keyBytes = Base64.getDecoder().decode(privateKeyClean)
        val keySpec = PKCS8EncodedKeySpec(keyBytes)
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(keySpec) as RSAPrivateKey

        // Crear JWT
        val now = System.currentTimeMillis()
        val exp = now + 3600_000 // 1 hora
        val algorithm = Algorithm.RSA256(null, privateKey)
        val jwt = JWT.create()
            .withIssuer(clientEmail)
            .withAudience(TOKEN_URL)
            .withClaim("scope", SCOPE)
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(exp))
            .sign(algorithm)

        // Intercambiar JWT por Access Token
        val body = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
            .add("assertion", jwt)
            .build()

        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(body)
            .build()

        val client = OkHttpClient()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Error token: ${response.code}")
            val jsonResp = JSONObject(response.body?.string() ?: "{}")
            return jsonResp.getString("access_token")
        }
    }
}
