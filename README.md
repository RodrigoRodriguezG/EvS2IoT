# Salmonitoreo - Sistema IoT/OT de Monitoreo y Control para Piscicultura

Sistema móvil nativo en Android (Kotlin) para el monitoreo y control de variables críticas en cultivo y recirculación de agua para piscicultura (enfocado en salmonicultura a pequeña escala), implementando comunicación bidireccional punto a punto mediante **Bluetooth RFCOMM** entre dos dispositivos con roles diferenciados: **Nodo Sensor** (simulador de hardware ESP32) y **Estación de Monitoreo** (panel de supervisión y comando de actuadores).

---

## 1. Problemática Resuelta

La salmonicultura requiere un control estricto de los parámetros físico-químicos del agua para asegurar la tasa de supervivencia, el bienestar de los salmónidos y la eficiencia operativa. En instalaciones a pequeña escala o estanques de cultivo en zonas remotas, la falta de infraestructura de red convencional (WiFi o cableado Ethernet) representa una barrera para la automatización y el monitoreo continuo.

**Salmonitoreo** resuelve este problema mediante una arquitectura distribuida de telemetría y control directo:

### Simulación de Hardware y Correspondencia del Prototipo ESP32

Para validar la lógica operativa sin requerir inicialmente la integración física con el microcontrolador de campo, la aplicación implementa el rol **Nodo Sensor**, que actúa como un simulador del hardware ESP32:

| Componente Físico ESP32 | Elemento Simulado en App (Nodo Sensor) | Función en la Piscicultura |
| :--- | :--- | :--- |
| **Sensor DHT22 / DS18B20** | **Slider de Temperatura** (0 a 40 °C) | Mide la temperatura del agua en el estanque. Rango óptimo del salmón: 18 °C a 26 °C. |
| **Potenciómetro / Sensor Analógico** | **Slider de Turbidez** (0 a 100 %) | Simula la concentración de sólidos suspendidos y calidad del agua. Límite admisible: $\le 60\%$. |
| **Actuador LED / Relé de Oxigenación** | **Indicador de Aireador** | Actuador de oxigenación/bombeo para compensar estrés térmico o turbidez elevada. |
| **Consola / SCADA de Planta** | **Estación de Monitoreo** | Panel central en tiempo real para visualizar parámetros, alertas y comandar actuadores. |

### Lógica de Control Operativa Implementada (`PiscicultureLogic`)

La lógica de control central está desacoplada de dependencias de Android en la clase pura `PiscicultureLogic.kt`:
1. **Regla del Aireador (Oxigenación):**
   - **Modo Automático (AUTO):** El aireador se activa automáticamente si la **temperatura está fuera de rango** ($< 18^\circ\text{C}$ o $> 26^\circ\text{C}$) **O** si la **turbidez supera el umbral crítico** ($> 60\%$).
   - **Modo Manual (ON / OFF):** El operador en la Estación de Monitoreo o en el Nodo puede forzar el encendido (`ON`) o apagado (`OFF`) del actuador independientemente de los valores de los sensores.
2. **Regla del Alimentador:**
   - Intervalo programado de alimentación automática cada **15 segundos** (15000 ms).
   - **Interbloqueo de seguridad:** La alimentación solo se ejecuta si el **aireador se encuentra apagado**, evitando dispersión de alimento por agitación excesiva de agua. Si el aireador está encendido, la alimentación se pospone y reintenta en cada ciclo de evaluación.

---

## 2. Justificación de la Arquitectura

### 2.1. Aplicación Nativa Android con Kotlin y Material Design 3
- Desarrollada 100% en **Kotlin**, aprovechando seguridad de nulos en tiempo de compilación y compatibilidad con APIs modernas de Android.
- Interfaz gráfica basada en **Material Design 3 (M3)**, utilizando temas oscuros de alto contraste optimizados para ambientes industriales y exteriores, componentes MaterialCardView, Chips de estado, Sliders accesibles y botones con elevación táctil (mínimo 48 dp).

### 2.2. Patrón MVVM con StateFlow y Corrutinas
- **Separación de Capas:**
  - **Model / Domain:** Lógica pura de negocio (`PiscicultureLogic`), modelos de datos inmutables y protocolo de comunicación agnóstico del sistema operativo.
  - **ViewModel:** `NodoViewModel` y `EstacionViewModel` exponen estados inmutables reactivos mediante `StateFlow` y eventos únicos con `SharedFlow`. Sobreviven a cambios de configuración como la rotación de pantalla.
  - **View:** `NodoActivity`, `EstacionActivity` y `LoginActivity` observan el estado usando `lifecycleScope.launch` con `repeatOnLifecycle(Lifecycle.State.STARTED)` para evitar fugas de memoria o trabajo en segundo plano cuando la actividad no es visible.
- **Concurrencia con Kotlin Coroutines:** Todo el trabajo de entrada/salida (`I/O`) en sockets de red Bluetooth, lectura de flujos con `BufferedReader`, latidos de sincronización y persistencia segura se ejecutan en `Dispatchers.IO`, manteniendo la interfaz gráfica fluida en `Dispatchers.Main` a 60 fps.

### 2.3. Bluetooth RFCOMM frente a Wi-Fi
- **Independencia de Infraestructura:** No requiere enrutadores, puntos de acceso (AP), cableado ni conexión a Internet en la zona de estanques.
- **Bajo Consumo Energético:** El perfil Serial Port Profile (SPP) de Bluetooth clásico sobre RFCOMM minimiza la demanda de batería en dispositivos móviles y hardware embebido.
- **Emparejamiento Directo y Estable:** Comunicación punto a punto con retransmisión garantizada a nivel de transporte (capa L2CAP/RFCOMM).

### 2.4. Autenticación y Persistencia Cifrada
- **Firebase Authentication:** Autenticación de operadores de planta basada en correo y contraseña sobre canales seguros **HTTPS/TLS**, gestionando tokens de acceso seguros.
- **EncryptedSharedPreferences (Jetpack Security):** Almacenamiento seguro en el dispositivo respaldado por la clave maestra del hardware de Android (`MasterKey` con `AES256_GCM` para valores y `AES256_SIV` para nombres de claves), permitiendo persistir sesiones de usuario de 24 horas sin guardar contraseñas en texto plano.

---

## 3. Especificación del Protocolo Inalámbrico

### 3.1. Transporte y Parámetros Físicos
- **Capa de Transporte:** Bluetooth Clásico con perfil **SPP (Serial Port Profile)** emulado sobre **RFCOMM**.
- **UUID de Servicio (en código):**
  ```kotlin
  val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
  ```
- **Nombre de Servicio SDP:** `"PisciculturaIoT_RFCOMM"`
- **Formato de Trama:** Texto plano estructurado en **JSON de una sola línea**, terminado estrictamente con el carácter de salto de línea `\n`.
- **Límite de Tamaño de Línea:** **2048 bytes (2 KB)** (`Protocol.MAX_LINE_BYTES`), previniendo saturación de memoria y ataques DoS.

### 3.2. Tabla de Mensajes del Protocolo

| Mensaje | Emisor $\to$ Receptor | Campos y Tipos | Descripción | Ejemplo en el Canal (`Wire`) |
| :--- | :--- | :--- | :--- | :--- |
| `HELLO` | Nodo $\leftrightarrow$ Estación | `type`: `"HELLO"`<br>`role`: `"NODO"` \| `"ESTACION"`<br>`user`: `String` | Negociación inicial de rol al aceptar la conexión. | `{"type":"HELLO","role":"NODO","user":"operador@salmon.cl"}\n` |
| `DATA` | Nodo $\to$ Estación | `type`: `"DATA"`<br>`ts`: `Long` (ms)<br>`temp`: `Float` (°C)<br>`turb`: `Float` (%)<br>`aireador`: `Boolean`<br>`manual`: `Boolean` | Telemetría periódica de variables y estado del actuador. | `{"type":"DATA","ts":1719150000000,"temp":22.4,"turb":35.2,"aireador":false,"manual":false}\n` |
| `CMD` (FEED) | Estación $\to$ Nodo | `type`: `"CMD"`<br>`cmd`: `"FEED"` | Comando de accionamiento forzado de alimentación. | `{"type":"CMD","cmd":"FEED"}\n` |
| `CMD` (AIREADOR) | Estación $\to$ Nodo | `type`: `"CMD"`<br>`cmd`: `"AIREADOR"`<br>`value`: `"ON"` \| `"OFF"` \| `"AUTO"` | Comando de control del modo del aireador. | `{"type":"CMD","cmd":"AIREADOR","value":"ON"}\n` |
| `ACK` | Nodo $\to$ Estación | `type`: `"ACK"`<br>`ref`: `String`<br>`ok`: `Boolean` | Acuse de recibo y ejecución exitosa de un comando. | `{"type":"ACK","ref":"AIREADOR","ok":true}\n` |
| `PING` | Cualquiera | `type`: `"PING"` | Latido (*Heartbeat*) de mantenimiento de enlace. | `{"type":"PING"}\n` |
| `PONG` | Cualquiera | `type`: `"PONG"` | Respuesta inmediata al latido `PING`. | `{"type":"PONG"}\n` |

### 3.3. Frecuencia y Control de Enlace (Heartbeat & Watchdog)
- **Envío de Telemetría (`DATA`):** Transmitido por el Nodo Sensor cada **2 segundos** (2000 ms) mientras haya una conexión activa.
- **Heartbeat (`PING`):** Tarea periódica de fondo que transmite un paquete `PING` cada **5 segundos** (5000 ms) de inactividad.
- **Watchdog de Timeout:** Si transcurren más de **10 segundos** (10000 ms) sin recibir ningún mensaje válido, `ConnectionManager` asume la desconexión del enlace, cierra los flujos y notifica el estado `ManagedConnectionState.Desconectado`.
- **Manejo de Errores y Robustez:**
  - `Protocol.parse()` evalúa las tramas dentro de un bloque protegido (`try/catch`). Si llega un paquete incompleto, JSON malformado o de longitud superior a 2048 bytes, la trama es descartada silenciosamente retornando `null` sin interrumpir la ejecución ni provocar caídas de la app.
  - Si la conexión se interrumpe físicamente, la interfaz de la Estación de Monitoreo presenta un mensaje informativo mediante `Snackbar` o estado visual, manteniendo la UI estable y habilitando el botón de **Reconectar** manualmente.

---

## 4. Medidas de Seguridad (Alineadas a ISO/IEC 27400)

En conformidad con las directrices de seguridad para sistemas de Internet de las Cosas (IoT) de la norma **ISO/IEC 27400:2022**:

1. **Autenticación Fuerte de Operadores:**
   - Acceso al sistema protegido por **Firebase Authentication** bajo canales cifrados de transporte TLS/HTTPS v1.3.
   - Mecanismo alternativo local seguro mediante derivación de claves **PBKDF2WithHmacSHA256** con salt aleatorio de 16 bytes y 10.000 iteraciones.
2. **Cero Almacenamiento de Contraseñas en Texto Plano:**
   - La aplicación jamás guarda, escribe en disco ni expone en logs (`Logcat`) las contraseñas de los usuarios. Las credenciales se procesan directamente como secuencias transitorias de caracteres y se desechan de memoria.
3. **Persistencia Cifrada de Sesión (At-Rest Encryption):**
   - El almacenamiento de tokens y datos de sesión utiliza la biblioteca oficial de Android Jetpack `EncryptedSharedPreferences`.
   - Cifrado simétrico de claves con `AES256_SIV` y valores con `AES256_GCM` gestionados a través de `MasterKey` vinculada al Android Keystore del hardware.
4. **Principio de Mínimo Privilegio en Permisos (Least Privilege):**
   - Declaración diferenciada por versión de Android en el manifiesto (`BLUETOOTH_SCAN` con flag `neverForLocation`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE` para Android 12+ / API 31+, y permisos restringidos para API $\le$ 30).
   - Solicitud de permisos en tiempo de ejecución (*runtime*) mediante `ActivityResultContracts.RequestMultiplePermissions()` con diálogo explicativo previo y manejo de denegación sin forzar cierres.
5. **Prevención de Fuga de Datos (Data Leak Prevention):**
   - `android:allowBackup="false"` explícitamente configurado en `AndroidManifest.xml` con reglas de extracción vacías, evitando la extracción de datos sensibles o llaves criptográficas mediante puentes de depuración (`adb backup`).
6. **Validación y Límite de Tamaño de Entradas de Red:**
   - Límite estricto de búfer de 2048 bytes por línea recibida para proteger contra desbordamientos y denegación de servicio en el canal Bluetooth.

---

## 5. Instrucciones de Compilación y Ejecución

### 5.1. Requisitos Previos
- **IDE:** Android Studio (Ladybug, Jellyfish o superior).
- **JDK:** Java Development Kit 17 o 21 configurado en Gradle.
- **Dispositivos:** 2 teléfonos celulares o tablets físicas con **Android 8.0 (Oreo / API 26) o superior** y soporte de hardware Bluetooth.

### 5.2. Configuración de Firebase
1. Ingrese a la [Consola de Firebase](https://console.firebase.google.com/) y cree un nuevo proyecto denominado `Salmonitoreo`.
2. Registre una nueva aplicación Android con el nombre de paquete del proyecto:
   ```
   cl.inacap.piscicultura
   ```
3. En la sección **Authentication > Métodos de inicio de sesión**, habilite el proveedor **Correo electrónico/Contraseña**.
4. Descargue el archivo de configuración `google-services.json` generado por Firebase y cópielo en el directorio `/app` del proyecto:
   ```
   Salmonitoreo/
   └── app/
       └── google-services.json
   ```

### 5.3. Compilación y Generación del APK
Desde la terminal en el directorio raíz del proyecto:
```bash
# En sistemas Linux / macOS:
./gradlew assembleDebug

# O mediante Gradle instalado:
gradle :app:assembleDebug
```
El archivo APK resultante se generará en:
```
app/build/outputs/apk/debug/app-debug.apk
```
También puede compilar directamente desde el menú de Android Studio: **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

### 5.4. Emparejamiento de Dispositivos (Paso Previo Indispensable)
El perfil Bluetooth SPP requiere que los teléfonos se reconozcan como dispositivos de confianza antes de abrir los sockets:
1. En el **Teléfono 1** y en el **Teléfono 2**, ingrese a **Ajustes > Conexiones > Bluetooth**.
2. Active la visibilidad de Bluetooth en ambos dispositivos.
3. Desde el **Teléfono 2**, busque el **Teléfono 1**, selecciónelo e ingrese el código PIN de confirmación para **Emparejarlos**.

### 5.5. Puesta en Marcha de la Aplicación
1. Instale el archivo `app-debug.apk` en ambos dispositivos móviles.
2. Abra la aplicación en ambos teléfonos y conceda los permisos de Bluetooth solicitados en pantalla.
3. Inicie sesión con una cuenta de Firebase registrada (o utilice el botón **Credenciales Demo** / **Acceso Rápido**).
4. En el selector de roles:
   - En el **Teléfono 1**: Seleccione el rol **"NODO SENSOR"** y presione **"Iniciar Servidor Bluetooth"**. El dispositivo quedará en estado de escucha (*Listening*).
   - En el **Teléfono 2**: Seleccione el rol **"ESTACIÓN DE MONITOREO"**, seleccione el **Teléfono 1** en la lista desplegable de dispositivos emparejados y presione **"Conectar"**.
5. Al establecerse el enlace:
   - Mueva los sliders de temperatura y turbidez en el Nodo Sensor; observará la actualización inmediata de telemetría y gráficos en la Estación.
   - Altere el modo del aireador desde la Estación (Auto / ON / OFF); observará el cambio de estado reflejado de inmediato en el Nodo.

### 5.6. Ubicación de Capturas de Pantalla

Para incorporar evidencia visual en el informe técnico o entrega, las capturas deben alojarse en el directorio `docs/screenshots/`:

```
docs/screenshots/
├── 01_login_activity.png         # Pantalla de autenticación y carga de credenciales
├── 02_permission_request.png     # Diálogo de solicitud de permisos Bluetooth
├── 03_role_select.png            # Pantalla de selección entre Nodo Sensor y Estación
├── 04_nodo_sensor_running.png    # Pantalla del Nodo Sensor con sliders y estado del aireador
└── 05_estacion_dashboard.png     # Dashboard de la Estación con métricas y control en tiempo real
```

---

## 6. Limitaciones Conocidas y Mejoras Futuras (Hacia la UA3)

### Limitaciones Actuales del Prototipo
1. **Emulación en Dispositivo Móvil:** Los parámetros físico-químicos del agua son ingresados mediante controles deslizantes (sliders) táctiles en un smartphone Android en lugar de transductores sumergidos reales.
2. **Cifrado en Capa de Aplicación:** Aunque la conexión RFCOMM se beneficia del cifrado del enlace Bluetooth a nivel de capa física y enlace mediante SSP (Secure Simple Pairing), las tramas JSON viajan en texto claro dentro del flujo de bytes.

### Mejoras Planificadas para la Unidad de Aprendizaje 3 (UA3)
1. **Conexión Real con Microcontrolador ESP32:**
   - Sustituir la aplicación móvil del Nodo Sensor por firmware en **C++ / FreeRTOS** compilado en un microcontrolador ESP32-WROOM-32.
   - Integración con sonda sumergible de temperatura **DS18B20** (protocolo 1-Wire) y sensor analógico de turbidez por dispersión óptica mediante el convertidor analógico-digital (ADC) del ESP32.
   - Conexión de salidas digitales a módulo de relé optoacoplado para control del aireador industrial y pin PWM para el servo SG90 de dosificación de pellets.
2. **Canal de Comunicación Seguro de Extremo a Extremo (E2EE):**
   - Implementación de un esquema de intercambio de llaves efímeras (ECDH / X25519) en el mensaje `HELLO` inicial.
   - Cifrado autenticado de cada trama JSON mediante algoritmo simétrico **AES-GCM (128 o 256 bits)** o **ChaCha20-Poly1305**, mitigando cualquier posibilidad de interceptación (*man-in-the-middle* o *sniffing*) en ambientes industriales no confiables.
3. **Resiliencia y Reintento Automático de Enlace:**
   - Cola local de almacenamiento de telemetría en memoria flash / SQLite cuando se pierde el enlace Bluetooth, con sincronización por lotes (*batch sync*) tan pronto como la Estación reanude la conexión.
