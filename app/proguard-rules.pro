# Reglas adicionales para release. ML Kit y Room traen sus propias reglas de consumidor.

# La base vive en :shared con @ConstructedBy. Según la guía de Room para KMP, con minify hace falta
# conservar el constructor de la implementación generada.
-keep class * extends androidx.room3.RoomDatabase { <init>(); }

# ML Kit trae WorkManager 2.7, que guarda su cola en una base con el Room viejo (androidx.room 2.2).
# La regla de ese Room conserva la clase generada (WorkDatabase_Impl) pero no su constructor, y R8 en
# modo completo lo quita: Room la crea por reflexión y, sin constructor, la app se cerraba al abrir,
# antes de llegar a nuestro código (WorkManager arranca solo con androidx.startup).
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# ML Kit arma sus piezas por reflexión (ComponentDiscovery crea cada «registrar» con su constructor
# sin argumentos). Sus reglas conservan la clase pero no ese constructor: sin esto «Revisar» fallaba
# al leer la letra («Attempt to invoke virtual method … on a null object reference»).
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }

# Archivo y línea en los errores: con el mapping.txt de la compilación (artefacto de Actions) se
# puede leer dónde falló.
-keepattributes SourceFile,LineNumberTable
