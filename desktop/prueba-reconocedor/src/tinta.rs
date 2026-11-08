//! Modelo de tinta compartido con la app Android (`ink/Ink.kt`).
//!
//! Formato v2 de `InkSerializer`: `{"v": 2, "s": [[x, y, t, p, x, y, t, p, ...], ...]}`, con x e y
//! normalizadas al ancho de la hoja (x va de 0 a 1). También se acepta el v1 sin presión.
//! La agrupación por líneas y el orden espacial son una copia fiel de `lineIndex` e `InkOrdering`,
//! para que el reconocedor de Windows reciba exactamente lo mismo que ML Kit en el celular.

use serde_json::Value;

pub const PRESION_MEDIA: f32 = 0.5;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Punto {
    pub x: f32,
    pub y: f32,
    pub t: i64,
    pub p: f32,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Trazo {
    pub puntos: Vec<Punto>,
}

impl Trazo {
    fn centro_y(&self) -> f32 {
        let (min, max) = self
            .puntos
            .iter()
            .fold((f32::MAX, f32::MIN), |(a, b), p| (a.min(p.y), b.max(p.y)));
        (min + max) / 2.0
    }

    fn min_x(&self) -> f32 {
        self.puntos.iter().map(|p| p.x).fold(f32::MAX, f32::min)
    }

    fn max_x(&self) -> f32 {
        self.puntos.iter().map(|p| p.x).fold(f32::MIN, f32::max)
    }

    /// Igual que `InkStroke.lineIndex`: la línea es la del centro vertical del trazo.
    pub fn linea(&self, alto_linea: f32) -> usize {
        (self.centro_y() / alto_linea).floor().max(0.0) as usize
    }
}

/// Lee la tinta en formato v2 (o v1). Las coordenadas quedan normalizadas al ancho (0..1).
pub fn leer(json: &Value) -> Result<Vec<Trazo>, String> {
    let (lista, paso) = match json {
        Value::Object(o) => {
            let v = o.get("v").and_then(Value::as_i64).unwrap_or(2);
            let s = o.get("s").and_then(Value::as_array).ok_or("Falta el campo \"s\" con los trazos")?;
            (s, if v >= 2 { 4 } else { 3 })
        }
        Value::Array(a) => (a, 3),
        Value::String(s) => {
            let interno: Value = serde_json::from_str(s).map_err(|e| format!("JSON de tinta inválido: {e}"))?;
            return leer(&interno);
        }
        _ => return Err("La tinta debe ser un objeto {\"v\":2,\"s\":[...]}".into()),
    };
    let mut trazos = Vec::with_capacity(lista.len());
    for arr in lista {
        let Some(nums) = arr.as_array() else { continue };
        let mut puntos = Vec::with_capacity(nums.len() / paso);
        for c in nums.chunks_exact(paso) {
            let f = |v: &Value| v.as_f64().unwrap_or(0.0);
            puntos.push(Punto {
                x: f(&c[0]) as f32,
                y: f(&c[1]) as f32,
                t: c[2].as_i64().unwrap_or_else(|| f(&c[2]) as i64),
                p: if paso == 4 { f(&c[3]) as f32 } else { PRESION_MEDIA },
            });
        }
        if !puntos.is_empty() {
            trazos.push(Trazo { puntos });
        }
    }
    Ok(trazos)
}

/// Agrupa por línea (en orden) y ordena cada línea como `InkOrdering.spatialOrder`.
pub fn por_lineas(trazos: &[Trazo], alto_linea: f32) -> Vec<(usize, Vec<Trazo>)> {
    let mut lineas: std::collections::BTreeMap<usize, Vec<Trazo>> = Default::default();
    for t in trazos.iter().filter(|t| !t.puntos.is_empty()) {
        lineas.entry(t.linea(alto_linea)).or_default().push(t.clone());
    }
    lineas.into_iter().map(|(n, ts)| (n, orden_espacial(&ts))).collect()
}

/// Copia de `InkOrdering.spatialOrder` (sin reescribir tiempos: Windows no los recibe).
///
/// Agrupa los trazos que se solapan en horizontal (sílabas/palabras), ordena los grupos de
/// izquierda a derecha y, dentro de cada grupo, respeta los tramos escritos de corrido. Así una
/// sílaba borrada y reescrita a mitad de línea vuelve a su lugar.
pub fn orden_espacial(trazos: &[Trazo]) -> Vec<Trazo> {
    let validos: Vec<&Trazo> = trazos.iter().filter(|t| !t.puntos.is_empty()).collect();
    if validos.len() <= 1 {
        return validos.into_iter().cloned().collect();
    }
    let mut limites: Vec<(usize, f32, f32)> =
        validos.iter().enumerate().map(|(i, t)| (i, t.min_x(), t.max_x())).collect();
    // sortedBy de Kotlin es estable, igual que sort_by.
    limites.sort_by(|a, b| a.1.total_cmp(&b.1));

    let mut grupos: Vec<Vec<usize>> = Vec::new();
    let mut max_grupo = f32::NEG_INFINITY;
    for (i, min_x, max_x) in limites {
        if grupos.is_empty() || min_x > max_grupo {
            grupos.push(vec![i]);
            max_grupo = max_x;
        } else {
            grupos.last_mut().unwrap().push(i);
            max_grupo = max_grupo.max(max_x);
        }
    }

    let mut ordenados = Vec::with_capacity(validos.len());
    for mut grupo in grupos {
        grupo.sort_unstable();
        let mut tramos: Vec<Vec<usize>> = Vec::new();
        let mut ultimo: isize = -2;
        let mut max_tramo = f32::NEG_INFINITY;
        for idx in grupo {
            let (min_x, max_x) = (validos[idx].min_x(), validos[idx].max_x());
            if tramos.is_empty() || idx as isize != ultimo + 1 || min_x > max_tramo {
                tramos.push(vec![idx]);
                max_tramo = max_x;
            } else {
                tramos.last_mut().unwrap().push(idx);
                max_tramo = max_tramo.max(max_x);
            }
            ultimo = idx as isize;
        }
        tramos.sort_by(|a, b| {
            let ma = a.iter().map(|&i| validos[i].min_x()).fold(f32::MAX, f32::min);
            let mb = b.iter().map(|&i| validos[i].min_x()).fold(f32::MAX, f32::min);
            ma.total_cmp(&mb)
        });
        ordenados.extend(tramos.into_iter().flatten().map(|i| validos[i].clone()));
    }
    ordenados
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn trazo(x0: f32, x1: f32, y: f32) -> Trazo {
        Trazo {
            puntos: vec![
                Punto { x: x0, y, t: 0, p: 0.5 },
                Punto { x: x1, y, t: 10, p: 0.5 },
            ],
        }
    }

    #[test]
    fn lee_v2_con_presion() {
        let t = leer(&json!({"v": 2, "s": [[0.1, 0.2, 5, 0.8, 0.3, 0.2, 9, 0.6]]})).unwrap();
        assert_eq!(t.len(), 1);
        assert_eq!(t[0].puntos[1], Punto { x: 0.3, y: 0.2, t: 9, p: 0.6 });
    }

    #[test]
    fn lee_v1_sin_presion_y_texto() {
        let t = leer(&json!("[[0.1, 0.2, 5, 0.3, 0.2, 9]]")).unwrap();
        assert_eq!(t[0].puntos.len(), 2);
        assert_eq!(t[0].puntos[0].p, PRESION_MEDIA);
    }

    #[test]
    fn rechaza_formato_raro() {
        assert!(leer(&json!(42)).is_err());
        assert!(leer(&json!({"v": 2})).is_err());
    }

    #[test]
    fn agrupa_por_centro_vertical() {
        let alto = 0.15;
        let ts = vec![trazo(0.1, 0.2, 0.05), trazo(0.1, 0.2, 0.32), trazo(0.3, 0.4, 0.07)];
        let lineas = por_lineas(&ts, alto);
        assert_eq!(lineas.iter().map(|l| l.0).collect::<Vec<_>>(), vec![0, 2]);
        assert_eq!(lineas[0].1.len(), 2);
    }

    #[test]
    fn silaba_reescrita_vuelve_a_su_lugar() {
        // Escrito: A (0.0–0.1), C (0.4–0.5) y al final se reescribe B (0.2–0.3) en medio.
        let a = trazo(0.0, 0.1, 0.05);
        let c = trazo(0.4, 0.5, 0.05);
        let b = trazo(0.2, 0.3, 0.05);
        let orden = orden_espacial(&[a.clone(), c.clone(), b.clone()]);
        assert_eq!(orden, vec![a, b, c]);
    }

    #[test]
    fn dentro_de_una_silaba_respeta_el_orden_de_escritura() {
        // Trazos que se solapan (misma sílaba): se mantiene el orden en que se escribieron.
        let t1 = trazo(0.10, 0.20, 0.05);
        let t2 = trazo(0.12, 0.18, 0.06);
        let t3 = trazo(0.11, 0.19, 0.07);
        let orden = orden_espacial(&[t1.clone(), t2.clone(), t3.clone()]);
        assert_eq!(orden, vec![t1, t2, t3]);
    }
}
