package com.example.estacionmeteorologica

import android.util.Patterns
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth

@Composable
fun AccesoMeteo() {
    val auth = remember { FirebaseAuth.getInstance() }
    var usuario by remember { mutableStateOf(auth.currentUser) }

    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener {
            usuario = it.currentUser
        }

        auth.addAuthStateListener(listener)

        onDispose {
            auth.removeAuthStateListener(listener)
        }
    }

    if (usuario == null) {
        PantallaLogin(auth)
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF163D60))
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Sesión iniciada",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )

                TextButton(onClick = { auth.signOut() }) {
                    Text(
                        text = "Cerrar sesión",
                        color = Color.White
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                AplicacionMeteo()
            }
        }
    }
}

@Composable
private fun PantallaLogin(auth: FirebaseAuth) {
    var correo by rememberSaveable { mutableStateOf("") }

    // La contraseña no se guarda al recrear la pantalla.
    var clave by remember { mutableStateOf("") }
    var mostrarClave by remember { mutableStateOf(false) }
    var cargando by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        FondoCielo(lluvia = false)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(20.dp))

            IconoClima(lluvia = false)

            Text(
                text = "METEO",
                color = Color.White,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp
            )

            Text(
                text = "Tu estación, siempre cerca",
                color = Color.White,
                fontSize = 16.sp
            )

            Spacer(Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFF4F8FD)
                )
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Bienvenido",
                        color = Color(0xFF173D64),
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "Inicia sesión para consultar tu estación.",
                        color = Color(0xFF526175),
                        fontSize = 13.sp
                    )

                    OutlinedTextField(
                        value = correo,
                        onValueChange = {
                            correo = it
                            error = null
                        },
                        label = { Text("Correo electrónico") },
                        singleLine = true,
                        enabled = !cargando,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = clave,
                        onValueChange = {
                            clave = it
                            error = null
                        },
                        label = { Text("Contraseña") },
                        singleLine = true,
                        enabled = !cargando,
                        visualTransformation = if (mostrarClave) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password
                        ),
                        trailingIcon = {
                            TextButton(
                                onClick = {
                                    mostrarClave = !mostrarClave
                                }
                            ) {
                                Text(
                                    if (mostrarClave) "Ocultar" else "Ver"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    error?.let { mensaje ->
                        Text(
                            text = mensaje,
                            color = Color(0xFFB42336),
                            fontSize = 12.sp
                        )
                    }

                    Button(
                        enabled = !cargando,
                        onClick = {
                            val correoLimpio = correo.trim()

                            if (
                                !Patterns.EMAIL_ADDRESS
                                    .matcher(correoLimpio)
                                    .matches()
                            ) {
                                error = "Ingresa un correo válido."
                            } else if (clave.isEmpty()) {
                                error = "Ingresa tu contraseña."
                            } else {
                                cargando = true
                                error = null

                                auth.signInWithEmailAndPassword(
                                    correoLimpio,
                                    clave
                                ).addOnCompleteListener { resultado ->
                                    cargando = false

                                    if (resultado.isSuccessful) {
                                        clave = ""
                                    } else {
                                        error = when (
                                            resultado.exception
                                        ) {
                                            is FirebaseNetworkException ->
                                                "No se pudo conectar. Revisa Internet."

                                            is FirebaseTooManyRequestsException ->
                                                "Demasiados intentos. Espera un momento."

                                            else ->
                                                "No se pudo iniciar sesión. Revisa el correo y la contraseña."
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF173D64)
                        )
                    ) {
                        if (cargando) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Iniciar sesión")
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}