# 🌱 EcoAguacate  
**Sistema móvil para la detección de plagas y evaluación de madurez en aguacates**

_Este proyecto fue desarrollado por **Enrique Ramírez Ruiz** durante su etapa universitaria como parte de un trabajo académico y de investigación aplicada._

EcoAguacate es una aplicación enfocada en brindar apoyo tanto a usuarios del área agrícola como urbana mediante el análisis inteligente de frutos. El sistema integra modelos de visión por computadora para identificar plagas presentes en el aguacate así como estimar su nivel de maduración. 

Este proyecto forma parte de una iniciativa para facilitar la toma de decisiones en productores, comerciantes y usuarios interesados en el manejo adecuado del fruto.

---

## 📌 Características principales

- 🔍 **Detección de plagas** mediante análisis visual del fruto.
- 🥑 **Clasificación del nivel de madurez del aguacate** utilizando modelos de Machine Learning.
- 📱 **Aplicación móvil Android** con arquitectura modular.
- ☁️ **Integración con servicios en la nube** (Google Cloud / Firebase) sin exponer credenciales en el repositorio.
- 🧠 **Procesamiento local y/o en la nube**, según el modelo.
- 🛡️ Buenas prácticas de **seguridad y versionado** (.gitignore y manejo de secretos).

---

## 🛠️ Tecnologías utilizadas

- **Android Studio**
- **Kotlin / Java** (según implementación)
- **Google ML Kit / Cloud Vision**
- **TensorFlow Lite (si aplica)**
- **Firebase** (Auth / Firestore / Storage, según necesidad)
- **Material Design**

---

## 📁 Estructura del proyecto (referencial)

```plaintext
EcoAguacate/
 ├── app/
 ├── build/
 ├── gradle/
 ├── .gitignore
 ├── README.md
 └── ...
```

## 🚀 Instalación y ejecución

1. **Clonar el repositorio:**
   ```bash
   git clone https://github.com/Enrique-Ramirez-Ruiz/EcoAguacate
   ```
2. **Abrir en Android Studio:**
   - Seleccionar *Open an existing project*
   - Elegir la carpeta del repositorio
3. **Sincronizar Gradle**
4. **Configurar servicios (opcional):**
   - Colocar tu archivo `serviceAccount.json` o las claves necesarias en una carpeta segura.
   - Configurar variables de entorno o rutas locales para el desarrollo.

---

## 🧪 Pruebas rápidas (sugerencia)

- Probar captura desde cámara y selección desde galería.
- Validar inferencia de madurez y reporte de posible plaga.
- Revisar comportamiento sin conexión (si aplica) y con conexión a servicios.

---

## 🧩 Roadmap

- [ ] Implementar modelos propios de clasificación.
- [ ] Añadir detección de más plagas.
- [ ] Entrenamiento con dataset personalizado.
- [ ] Generar API propia para inferencia.
- [ ] Versión iOS.
- [ ] Panel web de control.

---

## 🤝 Contribuciones

Las contribuciones son bienvenidas. Puedes abrir un **Issue** o enviar un **Pull Request**.

---

## 📄 Licencia

Este proyecto se publica con fines académicos.  

---

## 👨‍💻 Nota  
Proyecto desarrollado durante la universidad como apoyo a usuarios agrícolas y urbanos en la toma de decisiones relacionadas con el aguacate si deseas mejorarlo o aportar algo al proyecto eres bienvenido a realizarlo.
