package ar.jornadas

import android.app.Application

/** Dueña de las dependencias de todo el proceso: una sola Room, un solo Retrofit/OkHttp. */
class JornadasApp : Application() {
    val servicios by lazy { Servicios(this) }
}
