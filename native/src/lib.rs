//! Harbor's torrent engine for Android: librqbit (the same engine as Harbor desktop) plus its
//! HTTP API bound to 127.0.0.1. Kotlin only calls `start`; everything else (add a magnet,
//! stream a file with Range requests, stats) goes through the HTTP API.

use std::net::{Ipv4Addr, SocketAddr};
use std::path::PathBuf;
use std::sync::{Mutex, OnceLock};

use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use librqbit::dht::DhtPersistenceConfig;
use librqbit::http_api::{HttpApi, HttpApiOptions};
use librqbit::{Api, DhtSessionConfig, ListenerOptions, Session, SessionOptions};
use librqbit_dualstack_sockets::{BindOpts, TcpListener};

static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
static PORT: Mutex<Option<u16>> = Mutex::new(None);
static LAST_ERROR: Mutex<Option<String>> = Mutex::new(None);

fn runtime() -> &'static tokio::runtime::Runtime {
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(4)
            .thread_name("harbor-rqbit")
            .enable_all()
            .build()
            .expect("tokio runtime")
    })
}

fn start_engine(output_dir: PathBuf, state_dir: PathBuf) -> anyhow::Result<u16> {
    let mut port = PORT.lock().unwrap();
    if let Some(p) = *port {
        return Ok(p);
    }
    std::fs::create_dir_all(&output_dir)?;
    std::fs::create_dir_all(&state_dir)?;

    let p = runtime().block_on(async move {
        let opts = SessionOptions {
            // Remember DHT nodes between launches so peers are found faster next time.
            dht: Some(DhtSessionConfig {
                bootstrap_addrs: None,
                port: None,
                persistence: Some(DhtPersistenceConfig {
                    dump_interval: None,
                    config_filename: Some(state_dir.join("dht.json")),
                }),
            }),
            listen: Some(ListenerOptions::default()),
            persistence: None,
            fastresume: false,
            client_name_and_version: Some("Harbor-Android/0.1".to_string()),
            ..Default::default()
        };
        let session = Session::new_with_opts(output_dir, opts).await?;
        let api = Api::new(session, None, None);
        let listener = TcpListener::bind_tcp(
            SocketAddr::from((Ipv4Addr::LOCALHOST, 0)),
            BindOpts::default(),
        )?;
        let port = listener.bind_addr().port();
        let http = HttpApi::new(api, Some(HttpApiOptions::default()));
        tokio::spawn(http.make_http_api_and_run(listener, None));
        anyhow::Ok(port)
    })?;
    *port = Some(p);
    Ok(p)
}

fn string(env: &mut JNIEnv, s: &JString) -> anyhow::Result<String> {
    Ok(env.get_string(s)?.into())
}

#[no_mangle]
pub extern "system" fn Java_com_synrg_harbor_torrent_NativeEngine_start(
    mut env: JNIEnv,
    _class: JClass,
    output_dir: JString,
    state_dir: JString,
) -> jint {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let output = string(&mut env, &output_dir)?;
        let state = string(&mut env, &state_dir)?;
        start_engine(PathBuf::from(output), PathBuf::from(state))
    }));
    match result {
        Ok(Ok(port)) => port as jint,
        Ok(Err(e)) => {
            *LAST_ERROR.lock().unwrap() = Some(format!("{e:#}"));
            -1
        }
        Err(_) => {
            *LAST_ERROR.lock().unwrap() = Some("panic al iniciar el motor".to_string());
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_synrg_harbor_torrent_NativeEngine_lastError(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    match LAST_ERROR.lock().unwrap().clone() {
        Some(msg) => env
            .new_string(msg)
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}
