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

> Si lo deseas, actualizo esta sección con el stack exacto que uses en la app.

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

> 🔒 Las credenciales sensibles han sido excluidas mediante `.gitignore` para proteger el proyecto.

---

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
   - Colocar tu archivo `serviceAccount.json` o las claves necesarias en una carpeta segura **fuera del repositorio**.
   - Configurar variables de entorno o rutas locales para el desarrollo.

---

## 🧪 Pruebas rápidas (sugerencia)

- Probar captura desde cámara y selección desde galería.
- Validar inferencia de madurez y reporte de posible plaga.
- Revisar comportamiento sin conexión (si aplica) y con conexión a servicios.

---

## 📸 Capturas (opcional)

Crea una carpeta `assets/` en la raíz y agrega imágenes como:

```
assets/screenshot1.png
assets/screenshot2.png
```

Luego insértalas así:

```markdown
![Pantalla principal](assets/screenshot1.png)
```

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

Las contribuciones son bienvenidas. Puedes abrir un **Issue** o enviar un **Pull Request** siguiendo las buenas prácticas de Git.

---

## 📄 Licencia

Este proyecto se publica con fines académicos. Puedes añadir una licencia como **MIT** o **Apache-2.0**. Si me indicas cuál prefieres, la agrego y creo el archivo `LICENSE`.

---

## 👨‍💻 Autor

**Enrique Ramírez Ruiz**  
Proyecto desarrollado durante la universidad como apoyo a usuarios agrícolas y urbanos en la toma de decisiones relacionadas con el aguacate.
