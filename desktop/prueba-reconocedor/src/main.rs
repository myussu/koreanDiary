//! Prueba del reconocedor de escritura coreana de Windows.
//!
//! Abre una página local (http://127.0.0.1) donde se escribe con la tableta. La página manda la
//! tinta en el mismo formato que guarda la app Android y este programa la reconoce con el motor
//! de Windows, línea por línea como hace ML Kit en el celular, o la página entera de una vez.
//! Todo ocurre en esta computadora: nada sale a internet.

mod reconocedor;
mod tinta;

use reconocedor::{ANCHO_DIP, Palabra, Reconocedor, elegir_coreano};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::io::Read;
use std::time::Instant;
use tiny_http::{Header, Method, Request, Response, Server};

const PAGINA: &str = include_str!("pagina.html");
const MAX_CUERPO: usize = 20 * 1024 * 1024;

/// Alto de línea relativo al ancho de la hoja. En el celular son 64 dp sobre una hoja de unos
/// 412 dp; las muestras exportadas traen su propio valor.
const ALTO_LINEA_POR_DEFECTO: f32 = 64.0 / 412.0;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Pedido {
    tinta: Value,
    alto_linea: Option<f32>,
    reconocedor: Option<String>,
    /// "lineas" (por defecto, como el celular) o "pagina".
    modo: Option<String>,
}

#[derive(Serialize)]
struct Linea {
    linea: i64,
    texto: String,
    palabras: Vec<Palabra>,
    ms: f64,
}

fn main() {
    let rec = Reconocedor::nuevo();
    let servidor = match Server::http("127.0.0.1:0") {
        Ok(s) => s,
        Err(e) => {
            eprintln!("No se pudo abrir el servidor local: {e}");
            esperar_enter();
            return;
        }
    };
    let url = match servidor.server_addr().to_ip() {
        Some(a) => format!("http://{a}/"),
        None => {
            eprintln!("El servidor no tiene dirección IP");
            return;
        }
    };

    println!("Prueba del reconocedor de escritura coreana");
    println!("===========================================");
    match rec.reconocedores() {
        Ok(lista) => {
            println!("Reconocedores instalados en Windows:");
            for n in &lista {
                println!("  - {n}");
            }
            match elegir_coreano(&lista) {
                Some(k) => println!("Coreano: {k}"),
                None => println!(
                    "\nNo identifiqué el coreano por su nombre. Si está en la lista, elígelo en la página; \
                     si no, instálalo en Configuración → Hora e idioma → Idioma y región → Coreano → \
                     Opciones de idioma → Escritura a mano."
                ),
            }
        }
        Err(e) => println!("Error: {e}"),
    }
    println!("\nPágina de prueba: {url}");
    println!("(Si no se abrió sola, cópiala en el navegador. Cierra esta ventana para terminar.)\n");
    abrir_navegador(&url);

    for pedido in servidor.incoming_requests() {
        atender(&rec, pedido);
    }
}

fn atender(rec: &Reconocedor, mut req: Request) {
    let ruta = req.url().split('?').next().unwrap_or("/").to_string();
    let respuesta = match (req.method(), ruta.as_str()) {
        (Method::Get, "/") => html(PAGINA),
        (Method::Get, "/api/estado") => {
            let cuerpo = match rec.reconocedores() {
                Ok(lista) => json!({
                    "coreano": elegir_coreano(&lista),
                    "reconocedores": lista,
                    "altoLinea": ALTO_LINEA_POR_DEFECTO,
                    "version": env!("CARGO_PKG_VERSION"),
                }),
                Err(e) => json!({ "error": e, "reconocedores": [], "altoLinea": ALTO_LINEA_POR_DEFECTO }),
            };
            json_resp(&cuerpo)
        }
        (Method::Post, "/api/reconocer") => {
            let mut cuerpo = String::new();
            let leido = req.as_reader().take(MAX_CUERPO as u64).read_to_string(&mut cuerpo);
            let resultado = match leido {
                Ok(_) => reconocer(rec, &cuerpo),
                Err(e) => Err(format!("No se pudo leer el pedido: {e}")),
            };
            json_resp(&match resultado {
                Ok(v) => v,
                Err(e) => {
                    eprintln!("  error: {e}");
                    json!({ "ok": false, "error": e })
                }
            })
        }
        _ => Response::from_string("No encontrado").with_status_code(404),
    };
    let _ = req.respond(respuesta);
}

fn reconocer(rec: &Reconocedor, cuerpo: &str) -> Result<Value, String> {
    let pedido: Pedido = serde_json::from_str(cuerpo).map_err(|e| format!("Pedido inválido: {e}"))?;
    let trazos = tinta::leer(&pedido.tinta)?;
    if trazos.is_empty() {
        return Err("La hoja está vacía".into());
    }
    let alto = pedido.alto_linea.filter(|a| *a > 0.0).unwrap_or(ALTO_LINEA_POR_DEFECTO);
    let nombre = match pedido.reconocedor.filter(|n| !n.is_empty()) {
        Some(n) => n,
        None => elegir_coreano(&rec.reconocedores()?).ok_or("No hay reconocedor coreano instalado")?,
    };
    let modo_pagina = pedido.modo.as_deref() == Some("pagina");

    let inicio = Instant::now();
    let grupos = tinta::por_lineas(&trazos, alto);
    let mut lineas = Vec::new();
    if modo_pagina {
        // Toda la hoja en una sola llamada, en orden de lectura (líneas y, dentro, espacial).
        let todos: Vec<_> = grupos.iter().flat_map(|(_, ts)| ts.iter().cloned()).collect();
        let t0 = Instant::now();
        let palabras = rec.reconocer(&nombre, &todos, ANCHO_DIP, 0.0)?;
        lineas.push(Linea { linea: -1, texto: unir(&palabras), palabras, ms: ms(t0) });
    } else {
        for (n, ts) in &grupos {
            let t0 = Instant::now();
            // Igual que en el celular: cada línea se manda con su borde superior en y = 0.
            let dy = *n as f32 * alto * ANCHO_DIP;
            let palabras = rec.reconocer(&nombre, ts, ANCHO_DIP, dy)?;
            let texto = unir(&palabras);
            if !texto.is_empty() {
                lineas.push(Linea { linea: *n as i64, texto, palabras, ms: ms(t0) });
            }
        }
    }
    let total = ms(inicio);
    println!(
        "Reconocidas {} línea(s), {} trazo(s), modo {}, {:.0} ms",
        lineas.len(),
        trazos.len(),
        if modo_pagina { "página" } else { "líneas" },
        total
    );
    Ok(json!({
        "ok": true,
        "reconocedor": nombre,
        "modo": if modo_pagina { "pagina" } else { "lineas" },
        "trazos": trazos.len(),
        "lineas": lineas,
        "msTotal": total,
    }))
}

fn unir(palabras: &[Palabra]) -> String {
    palabras
        .iter()
        .filter_map(|p| p.candidatas.first())
        .map(|s| s.trim())
        .filter(|s| !s.is_empty())
        .collect::<Vec<_>>()
        .join(" ")
}

fn ms(t: Instant) -> f64 {
    (t.elapsed().as_secs_f64() * 10_000.0).round() / 10.0
}

fn cabecera(tipo: &str) -> Header {
    Header::from_bytes(&b"Content-Type"[..], tipo.as_bytes()).expect("cabecera válida")
}

fn html(s: &str) -> Response<std::io::Cursor<Vec<u8>>> {
    Response::from_string(s).with_header(cabecera("text/html; charset=utf-8"))
}

fn json_resp(v: &Value) -> Response<std::io::Cursor<Vec<u8>>> {
    Response::from_string(v.to_string()).with_header(cabecera("application/json; charset=utf-8"))
}

fn abrir_navegador(url: &str) {
    #[cfg(windows)]
    let _ = std::process::Command::new("cmd").args(["/C", "start", "", url]).spawn();
    #[cfg(not(windows))]
    let _ = url;
}

fn esperar_enter() {
    println!("Pulsa Enter para salir.");
    let _ = std::io::stdin().read_line(&mut String::new());
}
