package com.example.estacionmeteorologica

import android.os.Bundle
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private const val LIMITE_TEMPERATURA = 28f
private const val LIMITE_HUMEDAD = 80f
private const val LIMITE_AGUA = 1.5f

private val Blanco = Color.White
private val BlancoSuave = Color(0xFFDFECF8)
private val AzulOscuro = Color(0xFF173D64)

private data class Lectura(
    val hora: String,
    val temperatura: Float,
    val humedad: Float,
    val agua: Float,
    val instante: Long = System.currentTimeMillis()
)

private data class Aviso(
    val hora: String,
    val motivo: String,
    val activo: Boolean = true
)

private fun decimal(valor: Float): String =
    String.format(
        Locale.forLanguageTag("es-CL"),
        "%.1f",
        valor
    )

private fun horaActual(): String =
    SimpleDateFormat(
        "HH:mm:ss",
        Locale.getDefault()
    ).format(Date())

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = AzulOscuro,
                    surface = Color(0xFFF1F7FF)
                )
            ) {
                AccesoMeteo()
            }
        }
    }
}

@Composable
fun AplicacionMeteo() {
    var pagina by rememberSaveable { mutableStateOf(0) }

    var temperatura by rememberSaveable { mutableStateOf(25f) }
    var humedad by rememberSaveable { mutableStateOf(65f) }
    var agua by rememberSaveable { mutableStateOf(0f) }
    var lluvia by rememberSaveable { mutableStateOf(false) }

    var temperaturaAnterior by remember { mutableStateOf(25f) }
    var humedadAnterior by remember { mutableStateOf(65f) }
    var aguaAnterior by remember { mutableStateOf(0f) }

    var mostrarAviso by rememberSaveable { mutableStateOf(false) }
    var habiaAlerta by remember { mutableStateOf(false) }

    val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

    val database = remember {
        FirebaseDatabase.getInstance(
            "https://estacion-meteorologico-5c42d-default-rtdb.firebaseio.com/"
        )
    }

    val estacion = remember(uid) {
        database.getReference("usuarios")
            .child(uid)
            .child("estacion")
    }

    var emisor by rememberSaveable(uid) { mutableStateOf(false) }
    var menuRol by remember { mutableStateOf(false) }
    var conectado by remember { mutableStateOf(false) }
    var cargado by remember(uid) { mutableStateOf(false) }
    var tieneMedicion by remember(uid) { mutableStateOf(false) }
    var errorFirebase by remember(uid) { mutableStateOf<String?>(null) }
    var indiceMedicion by remember(uid) { mutableStateOf(0L) }

    val lecturas = remember(uid) {
        mutableStateListOf<Lectura>()
    }

    DisposableEffect(estacion) {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val actual = snapshot.child("actual")
                val nueva = actual.aLectura()

                if (nueva != null) {
                    temperaturaAnterior = temperatura
                    humedadAnterior = humedad
                    aguaAnterior = agua

                    temperatura = nueva.temperatura
                    humedad = nueva.humedad
                    agua = nueva.agua

                    lluvia = actual.child("lluvia").value
                            as? Boolean ?: false

                    indiceMedicion = (
                            actual.child("indice").value as? Number
                            )?.toLong() ?: 0L

                    tieneMedicion = true
                }

                val historial = snapshot.child("historial")
                    .children
                    .mapNotNull { it.aLectura() }
                    .sortedBy { it.instante }
                    .takeLast(60)

                lecturas.clear()
                lecturas.addAll(historial)

                cargado = true
                errorFirebase = null
            }

            override fun onCancelled(error: DatabaseError) {
                errorFirebase =
                    "No se pudieron leer las mediciones: ${error.message}"
                cargado = false
            }
        }

        val conexion = database.getReference(".info/connected")

        val conexionListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                conectado = snapshot.value == true
            }

            override fun onCancelled(error: DatabaseError) {
                conectado = false
            }
        }

        estacion.addValueEventListener(listener)
        conexion.addValueEventListener(conexionListener)

        onDispose {
            estacion.removeEventListener(listener)
            conexion.removeEventListener(conexionListener)
        }
    }

    val avisos = remember {
        mutableStateListOf<Aviso>()
    }

    val motivos = buildList {
        if (temperatura >= LIMITE_TEMPERATURA) {
            add(
                "Temperatura elevada: ${decimal(temperatura)} °C"
            )
        }

        if (humedad >= LIMITE_HUMEDAD) {
            add(
                "Humedad elevada: ${decimal(humedad)} %"
            )
        }

        if (agua >= LIMITE_AGUA) {
            add(
                "Nivel de agua elevado: ${decimal(agua)} cm"
            )
        }
    }

    val hayAlerta = tieneMedicion && motivos.isNotEmpty()

    // Solo el dispositivo Estación genera y publica mediciones.
    // El dispositivo Monitor recibe los cambios desde Firebase.
    LaunchedEffect(estacion, emisor, cargado, conectado) {
        if (!emisor || !cargado || !conectado) {
            return@LaunchedEffect
        }

        while (true) {
            if (tieneMedicion) {
                delay(3000L)
            }

            val siguiente = indiceMedicion + 1L

            val nuevaLluvia = if (siguiente % 10L == 0L) {
                !lluvia
            } else {
                lluvia
            }

            val objetivoTemperatura = if (nuevaLluvia) {
                21f
            } else {
                26f
            }

            val objetivoHumedad = if (nuevaLluvia) {
                85f
            } else {
                60f
            }

            val nuevaTemperatura = (
                    temperatura +
                            (objetivoTemperatura - temperatura) * 0.08f +
                            Random.nextFloat() * 0.4f - 0.2f
                    ).coerceIn(15f, 35f)

            val nuevaHumedad = (
                    humedad +
                            (objetivoHumedad - humedad) * 0.08f +
                            Random.nextFloat() - 0.5f
                    ).coerceIn(35f, 95f)

            val nuevaAgua = if (nuevaLluvia) {
                (
                        agua + 0.1f + Random.nextFloat() * 0.2f
                        ).coerceIn(0f, 10f)
            } else {
                (
                        agua - 0.1f - Random.nextFloat() * 0.2f
                        ).coerceIn(0f, 10f)
            }

            val medicion = mapOf<String, Any>(
                "temperatura" to nuevaTemperatura.toDouble(),
                "humedad" to nuevaHumedad.toDouble(),
                "agua" to nuevaAgua.toDouble(),
                "lluvia" to nuevaLluvia,
                "indice" to siguiente,
                "instante" to ServerValue.TIMESTAMP
            )

            try {
                // Conserva las últimas 60 mediciones en Firebase.
                estacion.guardarMedicion(
                    mapOf(
                        "actual" to medicion,
                        "historial/${siguiente % 60L}" to medicion
                    )
                )

                errorFirebase = null
            } catch (cancelacion: CancellationException) {
                throw cancelacion
            } catch (error: Exception) {
                errorFirebase =
                    "No se pudieron guardar las mediciones: " +
                            error.localizedMessage

                delay(3000L)
            }
        }
    }

    // Muestra un aviso por episodio de alerta.
    LaunchedEffect(hayAlerta) {
        if (hayAlerta && !habiaAlerta) {
            avisos.add(
                0,
                Aviso(
                    hora = horaActual(),
                    motivo = motivos.joinToString("\n")
                )
            )

            if (avisos.size > 20) {
                avisos.removeAt(avisos.lastIndex)
            }

            mostrarAviso = true
        }

        if (!hayAlerta && habiaAlerta) {
            for (indice in avisos.indices) {
                if (avisos[indice].activo) {
                    avisos[indice] = avisos[indice].copy(
                        activo = false
                    )
                }
            }

            mostrarAviso = false
        }

        habiaAlerta = hayAlerta
    }

    if (mostrarAviso && hayAlerta) {
        AlertDialog(
            onDismissRequest = { mostrarAviso = false },
            title = {
                Text(
                    "Alerta meteorológica",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(motivos.joinToString("\n\n"))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        mostrarAviso = false
                        pagina = 2
                    }
                ) {
                    Text("Ver alertas")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { mostrarAviso = false }
                ) {
                    Text("Entendido")
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        FondoCielo(lluvia)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = 20.dp,
                        vertical = 16.dp
                    ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "METEO",
                            color = Blanco,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 3.sp
                        )

                        Text(
                            "Estación 01 · ${
                                if (conectado) {
                                    "Conectada"
                                } else {
                                    "Sin conexión"
                                }
                            }",
                            color = BlancoSuave,
                            fontSize = 12.sp
                        )
                    }

                    Box {
                        TextButton(
                            onClick = { menuRol = true }
                        ) {
                            Text(
                                if (emisor) {
                                    "Estación ▾"
                                } else {
                                    "Monitor ▾"
                                },
                                color = Blanco
                            )
                        }

                        DropdownMenu(
                            expanded = menuRol,
                            onDismissRequest = { menuRol = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text("Estación: enviar mediciones")
                                },
                                onClick = {
                                    emisor = true
                                    menuRol = false
                                }
                            )

                            DropdownMenuItem(
                                text = {
                                    Text("Monitor: consultar mediciones")
                                },
                                onClick = {
                                    emisor = false
                                    menuRol = false
                                }
                            )
                        }
                    }

                    Text(
                        "DEMO",
                        color = Blanco,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(
                                Blanco.copy(alpha = 0.15f),
                                RoundedCornerShape(20.dp)
                            )
                            .padding(
                                horizontal = 12.dp,
                                vertical = 7.dp
                            )
                    )
                }

                if (errorFirebase != null) {
                    TarjetaCristal {
                        Text(
                            errorFirebase ?: "",
                            color = Blanco,
                            fontSize = 13.sp
                        )
                    }
                }

                if (!tieneMedicion) {
                    TarjetaCristal {
                        Text(
                            "Esperando mediciones",
                            color = Blanco,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            if (emisor) {
                                "Conectando con la estación…"
                            } else {
                                "Selecciona Estación en el dispositivo " +
                                        "que enviará las mediciones."
                            },
                            color = BlancoSuave,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    when (pagina) {
                        0 -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment =
                                    Alignment.CenterHorizontally
                            ) {
                                IconoClima(lluvia)

                                Text(
                                    text = "${decimal(temperatura)}°",
                                    color = Blanco,
                                    fontSize = 76.sp,
                                    fontWeight = FontWeight.Light
                                )

                                Text(
                                    text = if (lluvia) {
                                        "Lluvia en la estación"
                                    } else {
                                        "Cielo parcialmente nublado"
                                    },
                                    color = Blanco,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium
                                )

                                Text(
                                    text = "Temperatura · °C · ${
                                        tendencia(
                                            temperatura,
                                            temperaturaAnterior
                                        )
                                    }",
                                    color = BlancoSuave,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }

                            TarjetaCristal {
                                Text(
                                    text = if (hayAlerta) {
                                        "¡Atención a las condiciones!"
                                    } else {
                                        "Todo dentro de los límites"
                                    },
                                    color = Blanco,
                                    fontWeight = FontWeight.SemiBold
                                )

                                Text(
                                    text = if (hayAlerta) {
                                        motivos.joinToString("\n")
                                    } else {
                                        "Los tres sensores están dentro " +
                                                "de los límites configurados."
                                    },
                                    color = BlancoSuave,
                                    fontSize = 12.sp
                                )

                                if (hayAlerta) {
                                    TextButton(
                                        onClick = { pagina = 2 }
                                    ) {
                                        Text(
                                            "Consultar alertas",
                                            color = Blanco
                                        )
                                    }
                                }
                            }

                            Row(
                                horizontalArrangement =
                                    Arrangement.spacedBy(12.dp)
                            ) {
                                TarjetaDato(
                                    titulo = "HUMEDAD",
                                    valor = decimal(humedad),
                                    unidad = "%",
                                    tendencia = tendencia(
                                        humedad,
                                        humedadAnterior
                                    ),
                                    modifier = Modifier.weight(1f)
                                )

                                TarjetaDato(
                                    titulo = "AGUA",
                                    valor = decimal(agua),
                                    unidad = "cm",
                                    tendencia = tendencia(
                                        agua,
                                        aguaAnterior
                                    ),
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            TarjetaCristal {
                                Text(
                                    "Temperatura reciente",
                                    color = Blanco,
                                    fontWeight = FontWeight.SemiBold
                                )

                                GraficaCielo(
                                    valores = lecturas
                                        .takeLast(20)
                                        .map { it.temperatura },
                                    unidad = "°C"
                                )
                            }
                        }

                        1 -> {
                            Text(
                                "Historial",
                                color = Blanco,
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                "Últimas mediciones de la estación",
                                color = BlancoSuave,
                                fontSize = 13.sp
                            )

                            PantallaHistorial(lecturas.toList())
                        }

                        2 -> {
                            Text(
                                "Alertas",
                                color = Blanco,
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Bold
                            )

                            TarjetaCristal {
                                Text(
                                    text = if (hayAlerta) {
                                        "Alerta activa"
                                    } else {
                                        "Sin alertas activas"
                                    },
                                    color = Blanco,
                                    fontWeight = FontWeight.Bold
                                )

                                Text(
                                    text = if (hayAlerta) {
                                        motivos.joinToString("\n")
                                    } else {
                                        "Las mediciones están dentro " +
                                                "de los límites."
                                    },
                                    color = BlancoSuave,
                                    fontSize = 13.sp
                                )
                            }

                            TarjetaCristal {
                                Text(
                                    "Límites de prueba",
                                    color = Blanco,
                                    fontWeight = FontWeight.SemiBold
                                )

                                Text(
                                    "Temperatura ≥ " +
                                            "${decimal(LIMITE_TEMPERATURA)} °C\n" +
                                            "Humedad ≥ " +
                                            "${decimal(LIMITE_HUMEDAD)} %\n" +
                                            "Agua ≥ " +
                                            "${decimal(LIMITE_AGUA)} cm",
                                    color = BlancoSuave,
                                    fontSize = 13.sp
                                )
                            }

                            Text(
                                "Registro de esta sesión",
                                color = Blanco,
                                fontWeight = FontWeight.SemiBold
                            )

                            if (avisos.isEmpty()) {
                                TarjetaCristal {
                                    Text(
                                        "Aún no se han registrado alertas.",
                                        color = BlancoSuave,
                                        fontSize = 13.sp
                                    )
                                }
                            }

                            avisos.forEach { aviso ->
                                TarjetaCristal {
                                    Text(
                                        "${aviso.hora} · ${
                                            if (aviso.activo) {
                                                "Activa"
                                            } else {
                                                "Resuelta"
                                            }
                                        }",
                                        color = Blanco,
                                        fontWeight = FontWeight.SemiBold
                                    )

                                    Text(
                                        aviso.motivo,
                                        color = BlancoSuave,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
            }

            NavigationBar(
                containerColor = Color(0xFF163D60).copy(alpha = 0.95f),
                contentColor = Blanco,
                windowInsets = WindowInsets(0, 0, 0, 0)
            ) {
                val nombres = listOf(
                    "Inicio",
                    "Historial",
                    "Alertas"
                )

                val simbolos = listOf("☀", "≋", "!")

                nombres.forEachIndexed { indice, nombre ->
                    NavigationBarItem(
                        selected = pagina == indice,
                        onClick = { pagina = indice },
                        icon = {
                            Text(
                                text = if (indice == 2 && hayAlerta) {
                                    "! •"
                                } else {
                                    simbolos[indice]
                                },
                                fontSize = 23.sp,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        label = {
                            Text(nombre)
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Blanco,
                            selectedTextColor = Blanco,
                            unselectedIconColor = BlancoSuave,
                            unselectedTextColor = BlancoSuave,
                            indicatorColor =
                                Blanco.copy(alpha = 0.18f)
                        )
                    )
                }
            }
        }
    }
}

private fun DataSnapshot.aLectura(): Lectura? {
    val t = (
            child("temperatura").value as? Number
            )?.toFloat() ?: return null

    val h = (
            child("humedad").value as? Number
            )?.toFloat() ?: return null

    val a = (
            child("agua").value as? Number
            )?.toFloat() ?: return null

    val instante = (
            child("instante").value as? Number
            )?.toLong() ?: return null

    if (!t.isFinite() || !h.isFinite() || !a.isFinite()) {
        return null
    }

    return Lectura(
        hora = SimpleDateFormat(
            "HH:mm:ss",
            Locale.getDefault()
        ).format(Date(instante)),
        temperatura = t,
        humedad = h,
        agua = a,
        instante = instante
    )
}

private suspend fun DatabaseReference.guardarMedicion(
    datos: Map<String, Any>
) {
    suspendCancellableCoroutine<Unit> { continuacion ->
        updateChildren(datos)
            .addOnSuccessListener {
                if (continuacion.isActive) {
                    continuacion.resume(Unit)
                }
            }
            .addOnFailureListener { error ->
                if (continuacion.isActive) {
                    continuacion.resumeWithException(error)
                }
            }
    }
}

private fun tendencia(
    actual: Float,
    anterior: Float
): String {
    val diferencia = actual - anterior

    return when {
        diferencia > 0.05f -> "↑ Subiendo"
        diferencia < -0.05f -> "↓ Bajando"
        else -> "Estable"
    }
}

@Composable
fun TarjetaCristal(
    modifier: Modifier = Modifier,
    contenido: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF153D61).copy(alpha = 0.62f)
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = contenido
        )
    }
}

@Composable
fun TarjetaDato(
    titulo: String,
    valor: String,
    unidad: String,
    tendencia: String,
    modifier: Modifier = Modifier
) {
    TarjetaCristal(modifier) {
        Text(
            titulo,
            color = BlancoSuave,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            valor,
            color = Blanco,
            fontSize = 32.sp,
            fontWeight = FontWeight.Light
        )

        Text(
            unidad,
            color = BlancoSuave,
            fontSize = 14.sp
        )

        Text(
            tendencia,
            color = Blanco,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun PantallaHistorial(lecturas: List<Lectura>) {
    var variable by rememberSaveable {
        mutableStateOf(0)
    }

    val nombres = listOf("Temp.", "Humedad", "Agua")
    val unidad = listOf("°C", "%", "cm")[variable]

    val valores = lecturas.map {
        when (variable) {
            0 -> it.temperatura
            1 -> it.humedad
            else -> it.agua
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        nombres.forEachIndexed { indice, nombre ->
            FilterChip(
                selected = variable == indice,
                onClick = { variable = indice },
                label = { Text(nombre) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color(0xFF214E75),
                    labelColor = Blanco,
                    selectedContainerColor = Blanco,
                    selectedLabelColor = AzulOscuro
                )
            )
        }
    }

    TarjetaCristal {
        Text(
            "Últimas ${lecturas.size} lecturas",
            color = Blanco,
            fontWeight = FontWeight.SemiBold
        )

        GraficaCielo(valores, unidad)
    }

    lecturas.takeLast(12).reversed().forEach { lectura ->
        val valor = when (variable) {
            0 -> lectura.temperatura
            1 -> lectura.humedad
            else -> lectura.agua
        }

        TarjetaCristal {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    lectura.hora,
                    color = BlancoSuave,
                    fontSize = 13.sp
                )

                Text(
                    "${decimal(valor)} $unidad",
                    color = Blanco,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun FondoCielo(lluvia: Boolean) {
    val superior by animateColorAsState(
        targetValue = if (lluvia) {
            Color(0xFF253C59)
        } else {
            Color(0xFF1263AC)
        },
        animationSpec = tween(1800),
        label = "Color superior"
    )

    val inferior by animateColorAsState(
        targetValue = if (lluvia) {
            Color(0xFF718B9E)
        } else {
            Color(0xFF76C4E8)
        },
        animationSpec = tween(1800),
        label = "Color inferior"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(superior, inferior)
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            fun nube(
                x: Float,
                y: Float,
                escala: Float
            ) {
                val color = Blanco.copy(alpha = 0.10f)

                drawOval(
                    color,
                    topLeft = Offset(x, y),
                    size = Size(escala * 2.6f, escala)
                )

                drawCircle(
                    color,
                    radius = escala * 0.6f,
                    center = Offset(x + escala, y)
                )

                drawCircle(
                    color,
                    radius = escala * 0.45f,
                    center = Offset(
                        x + escala * 1.75f,
                        y + escala * 0.05f
                    )
                )
            }

            nube(
                x = -size.width * 0.20f,
                y = size.height * 0.16f,
                escala = size.width * 0.36f
            )

            nube(
                x = size.width * 0.64f,
                y = size.height * 0.40f,
                escala = size.width * 0.30f
            )

            nube(
                x = -size.width * 0.15f,
                y = size.height * 0.78f,
                escala = size.width * 0.42f
            )
        }
    }
}

@Composable
fun IconoClima(lluvia: Boolean) {
    val transicion = rememberInfiniteTransition(
        label = "Clima"
    )

    val movimiento by transicion.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 2500,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Movimiento de nube"
    )

    val caida by transicion.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 900,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "Lluvia"
    )

    Canvas(
        modifier = Modifier.size(
            width = 180.dp,
            height = 120.dp
        )
    ) {
        val w = size.width
        val h = size.height
        val desplazamiento = movimiento.dp.toPx()

        if (!lluvia) {
            val centroSol = Offset(
                w * 0.38f,
                h * 0.36f
            )

            val radio = w * 0.14f

            for (i in 0 until 8) {
                val angulo = i * Math.PI / 4

                val inicio = Offset(
                    centroSol.x +
                            cos(angulo).toFloat() * radio * 1.3f,
                    centroSol.y +
                            sin(angulo).toFloat() * radio * 1.3f
                )

                val fin = Offset(
                    centroSol.x +
                            cos(angulo).toFloat() * radio * 1.65f,
                    centroSol.y +
                            sin(angulo).toFloat() * radio * 1.65f
                )

                drawLine(
                    color = Color(0xFFFFD986),
                    start = inicio,
                    end = fin,
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            drawCircle(
                color = Color(0xFFFFD986),
                radius = radio,
                center = centroSol
            )
        }

        val nube = if (lluvia) {
            Color(0xFFD0E0EF)
        } else {
            Blanco
        }

        drawRoundRect(
            color = nube,
            topLeft = Offset(
                w * 0.23f + desplazamiento,
                h * 0.49f
            ),
            size = Size(
                w * 0.60f,
                h * 0.25f
            ),
            cornerRadius =
                androidx.compose.ui.geometry.CornerRadius(
                    h * 0.13f,
                    h * 0.13f
                )
        )

        drawCircle(
            color = nube,
            radius = h * 0.22f,
            center = Offset(
                w * 0.48f + desplazamiento,
                h * 0.47f
            )
        )

        drawCircle(
            color = nube,
            radius = h * 0.16f,
            center = Offset(
                w * 0.66f + desplazamiento,
                h * 0.52f
            )
        )

        if (lluvia) {
            for (i in 0..3) {
                val x = w * (0.35f + i * 0.12f)
                val progreso = (caida + i * 0.22f) % 1f
                val y = h * (0.77f + progreso * 0.15f)

                drawLine(
                    color = Color(0xFFB4E6FF),
                    start = Offset(x, y),
                    end = Offset(
                        x - 3.dp.toPx(),
                        y + 7.dp.toPx()
                    ),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

@Composable
fun GraficaCielo(
    valores: List<Float>,
    unidad: String
) {
    if (valores.isEmpty()) return

    val margenEscala = if (unidad == "%") {
        1f
    } else {
        0.5f
    }

    val minimo = (valores.minOrNull() ?: 0f) - margenEscala
    val maximo = (valores.maxOrNull() ?: 0f) + margenEscala
    val rango = maximo - minimo

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "${decimal(maximo)} $unidad",
            color = BlancoSuave,
            fontSize = 10.sp
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(105.dp)
        ) {
            val margen = 6.dp.toPx()
            val ancho = size.width - margen * 2
            val alto = size.height - margen * 2

            for (i in 0..3) {
                val y = margen + alto * i / 3f

                drawLine(
                    color = Blanco.copy(alpha = 0.12f),
                    start = Offset(margen, y),
                    end = Offset(size.width - margen, y),
                    strokeWidth = 1.dp.toPx()
                )
            }

            val puntos = valores.mapIndexed { indice, valor ->
                Offset(
                    x = margen + ancho * indice /
                            (valores.size - 1).coerceAtLeast(1),
                    y = margen + alto * (
                            1f - (valor - minimo) / rango
                            )
                )
            }

            val linea = Path().apply {
                puntos.forEachIndexed { indice, punto ->
                    if (indice == 0) {
                        moveTo(punto.x, punto.y)
                    } else {
                        lineTo(punto.x, punto.y)
                    }
                }
            }

            if (puntos.size > 1) {
                val relleno = Path().apply {
                    moveTo(
                        puntos.first().x,
                        size.height - margen
                    )

                    puntos.forEach {
                        lineTo(it.x, it.y)
                    }

                    lineTo(
                        puntos.last().x,
                        size.height - margen
                    )

                    close()
                }

                drawPath(
                    path = relleno,
                    brush = Brush.verticalGradient(
                        listOf(
                            Blanco.copy(alpha = 0.24f),
                            Color.Transparent
                        )
                    )
                )

                drawPath(
                    path = linea,
                    color = Blanco,
                    style = Stroke(
                        width = 2.5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                )
            }

            drawCircle(
                color = Color(0xFFFFDE95),
                radius = 4.dp.toPx(),
                center = puntos.last()
            )
        }

        Text(
            "${decimal(minimo)} $unidad",
            color = BlancoSuave,
            fontSize = 10.sp
        )
    }
}