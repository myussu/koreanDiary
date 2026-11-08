//! Reconocedor de escritura de Windows (WinRT, `Windows.UI.Input.Inking`).
//!
//! Es el mismo motor que usa el panel de escritura a mano de Windows. Trabaja con trazos
//! (no con imágenes), igual que ML Kit: recibe puntos con presión y devuelve, por cada palabra
//! que detecta, varias candidatas. El modelo coreano se instala como función opcional de Windows
//! (Configuración → Hora e idioma → Idioma y región → Coreano → Escritura a mano).

use crate::tinta::Trazo;
use serde::Serialize;

#[derive(Serialize, Debug, Clone)]
pub struct Palabra {
    /// Candidatas en el orden que da Windows: la primera es la más probable.
    pub candidatas: Vec<String>,
}

/// Ancho de la hoja en DIP para Windows. La tinta llega normalizada (x de 0 a 1); el reconocedor
/// espera coordenadas de pantalla, así que se escala a una hoja de tamaño típico.
pub const ANCHO_DIP: f32 = 1000.0;

#[cfg(windows)]
mod imp {
    use super::*;
    use windows::Foundation::Point;
    use windows::UI::Input::Inking::{
        InkPoint, InkRecognitionTarget, InkRecognizer, InkRecognizerContainer, InkStrokeBuilder,
        InkStrokeContainer,
    };
    use windows::Win32::System::WinRT::{RO_INIT_MULTITHREADED, RoInitialize};
    use windows_collections::IIterable;
    use windows_numerics::Matrix3x2;

    fn texto(e: windows_core::Error) -> String {
        format!("{} (HRESULT 0x{:08X})", e.message(), e.code().0 as u32)
    }

    pub struct Reconocedor {
        contenedor: Result<InkRecognizerContainer, String>,
    }

    impl Reconocedor {
        pub fn nuevo() -> Self {
            // Los objetos de tinta no son "agile": se crean y se usan siempre en este hilo.
            // Si el hilo ya estaba inicializado, RoInitialize falla sin consecuencias.
            unsafe {
                let _ = RoInitialize(RO_INIT_MULTITHREADED);
            }
            let contenedor = InkRecognizerContainer::new().map_err(|e| {
                format!(
                    "Windows no tiene un motor de reconocimiento disponible: {}. \
                     ¿Está instalada la escritura a mano de algún idioma?",
                    texto(e)
                )
            });
            Self { contenedor }
        }

        fn lista_interna(&self) -> Result<Vec<(String, InkRecognizer)>, String> {
            let c = self.contenedor.as_ref().map_err(Clone::clone)?;
            let v = c.GetRecognizers().map_err(texto)?;
            let mut out = Vec::new();
            for i in 0..v.Size().map_err(texto)? {
                let r = v.GetAt(i).map_err(texto)?;
                out.push((r.Name().map_err(texto)?.to_string_lossy(), r));
            }
            Ok(out)
        }

        pub fn reconocedores(&self) -> Result<Vec<String>, String> {
            Ok(self.lista_interna()?.into_iter().map(|(n, _)| n).collect())
        }

        /// Reconoce un grupo de trazos (una línea o la página entera) ya ordenado y desplazado.
        /// `escala` convierte las coordenadas normalizadas a DIP.
        pub fn reconocer(&self, nombre: &str, trazos: &[Trazo], escala: f32, dy: f32) -> Result<Vec<Palabra>, String> {
            let c = self.contenedor.as_ref().map_err(Clone::clone)?;
            let rec = self
                .lista_interna()?
                .into_iter()
                .find(|(n, _)| n == nombre)
                .map(|(_, r)| r)
                .ok_or_else(|| format!("No existe el reconocedor «{nombre}»"))?;
            c.SetDefaultRecognizer(&rec).map_err(texto)?;

            let constructor = InkStrokeBuilder::new().map_err(texto)?;
            let contenedor = InkStrokeContainer::new().map_err(texto)?;
            for t in trazos {
                let mut puntos: Vec<Option<InkPoint>> = Vec::with_capacity(t.puntos.len() + 1);
                for p in &t.puntos {
                    let pos = Point { X: p.x * escala, Y: p.y * escala - dy };
                    puntos.push(Some(InkPoint::CreateInkPoint(pos, p.p.clamp(0.0, 1.0)).map_err(texto)?));
                }
                // Un punto suelto (un punto de la i, una tilde) se duplica: un trazo necesita dos.
                if puntos.len() == 1 {
                    puntos.push(puntos[0].clone());
                }
                let iterable: IIterable<InkPoint> = puntos.into();
                let trazo = constructor
                    .CreateStrokeFromInkPoints(&iterable, Matrix3x2::identity())
                    .map_err(texto)?;
                contenedor.AddStroke(&trazo).map_err(texto)?;
            }

            let resultados = c
                .RecognizeAsync(&contenedor, InkRecognitionTarget::All)
                .map_err(texto)?
                .join()
                .map_err(texto)?;
            let mut palabras = Vec::new();
            for i in 0..resultados.Size().map_err(texto)? {
                let r = resultados.GetAt(i).map_err(texto)?;
                let cands = r.GetTextCandidates().map_err(texto)?;
                let mut candidatas = Vec::new();
                for j in 0..cands.Size().map_err(texto)? {
                    candidatas.push(cands.GetAt(j).map_err(texto)?.to_string_lossy());
                }
                if !candidatas.is_empty() {
                    palabras.push(Palabra { candidatas });
                }
            }
            Ok(palabras)
        }
    }
}

/// Fuera de Windows (para compilar y probar el resto en Linux): siempre falla con un aviso claro.
#[cfg(not(windows))]
mod imp {
    use super::*;

    pub struct Reconocedor;

    const AVISO: &str = "El reconocedor de escritura solo existe en Windows";

    impl Reconocedor {
        pub fn nuevo() -> Self {
            Self
        }
        pub fn reconocedores(&self) -> Result<Vec<String>, String> {
            Err(AVISO.into())
        }
        pub fn reconocer(&self, _: &str, _: &[Trazo], _: f32, _: f32) -> Result<Vec<Palabra>, String> {
            Err(AVISO.into())
        }
    }
}

pub use imp::Reconocedor;

/// Elige el reconocedor coreano por su nombre (Windows lo nombra en el idioma de la interfaz).
pub fn elegir_coreano(nombres: &[String]) -> Option<String> {
    const PISTAS: [&str; 4] = ["한국어", "korean", "coreano", "korea"];
    nombres
        .iter()
        .find(|n| {
            let n = n.to_lowercase();
            PISTAS.iter().any(|p| n.contains(p))
        })
        .cloned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn encuentra_el_coreano_en_cualquier_idioma() {
        let n = |v: &[&str]| v.iter().map(|s| s.to_string()).collect::<Vec<_>>();
        assert_eq!(
            elegir_coreano(&n(&["Microsoft English (US) Handwriting Recognizer", "Microsoft 한국어 필기 인식기"])),
            Some("Microsoft 한국어 필기 인식기".into())
        );
        assert!(elegir_coreano(&n(&["Reconocedor de escritura a mano de Microsoft para coreano"])).is_some());
        assert_eq!(elegir_coreano(&n(&["Microsoft English (US) Handwriting Recognizer"])), None);
    }
}
