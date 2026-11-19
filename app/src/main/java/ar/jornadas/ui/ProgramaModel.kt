package ar.jornadas.ui

import android.app.Application
import androidx.lifecycle.*
import ar.jornadas.JornadasApp
import ar.jornadas.data.Charla
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.IOException

sealed interface ProgramaEstado {
    /** Primera descarga en curso y todavía no hay ninguna edición guardada. */
    data object Cargando : ProgramaEstado
    /** Nunca se descargó una edición: se ofrece reintentar. */
    data object SinEdicion : ProgramaEstado
    /** Hay una edición guardada (puede estar vacía); si la última descarga falló se avisa. */
    data class Disponible(val charlas: List<Charla>, val usandoGuardados: Boolean) : ProgramaEstado
}

class ProgramaModel(app: Application, private val guardado: SavedStateHandle) : AndroidViewModel(app) {
    private val servicios = (app as JornadasApp).servicios
    val mesa = servicios.mesa
    private val repo = servicios.agenda
    val charlas = repo.programa.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** Filtros de lectura: sobreviven a la rotación y a que Android mate el proceso. */
    val dia: StateFlow<Int> = guardado.getStateFlow("dia", 0)
    val tema: StateFlow<String> = guardado.getStateFlow("tema", "Todos")
    fun elegirDia(valor: Int) { guardado["dia"] = valor }
    fun elegirTema(valor: String) { guardado["tema"] = valor }
    private val fallo = MutableStateFlow(false)
    val cargando = MutableStateFlow(false)
    val estado: StateFlow<ProgramaEstado> =
        combine(repo.hayEdicion, repo.programa, fallo, cargando) { hay, lista, fallo, cargando ->
            when {
                hay -> ProgramaEstado.Disponible(lista, usandoGuardados = fallo)
                cargando -> ProgramaEstado.Cargando
                else -> ProgramaEstado.SinEdicion
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgramaEstado.Cargando)

    init { if (servicios.reclamarCargaAutomatica()) recargar() }

    fun recargar() {
        if (!cargando.compareAndSet(false, true)) return
        fallo.value = false
        viewModelScope.launch {
            try {
                repo.sincronizar()
            } catch (e: IOException) {
                fallo.value = true
            } finally {
                cargando.value = false
            }
        }
    }

    fun marcar(id: String) { viewModelScope.launch { repo.cambiarFavorita(id) } }
}
