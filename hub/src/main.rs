macro_rules! info { ($($a:tt)*) => { eprintln!("INFO  {}", format!($($a)*)) } }
macro_rules! warn { ($($a:tt)*) => { eprintln!("WARN  {}", format!($($a)*)) } }
macro_rules! error { ($($a:tt)*) => { eprintln!("ERROR {}", format!($($a)*)) } }

mod api;
mod config;
mod crypto;
mod fcm;
mod hub;
mod listener;
mod names;
mod raid;
mod sb;
mod store;

use std::time::Duration;

async fn run() -> Result<(), String> {
    rustls::crypto::ring::default_provider()
        .install_default()
        .map_err(|_| "could not install the rustls crypto provider".to_string())?;
    let cfg = config::Config::from_env()?;
    let http = reqwest::Client::builder()
        .timeout(Duration::from_secs(20))
        .user_agent("kd-pager-hub/0.1")
        .build()
        .map_err(|e| format!("http client: {e}"))?;

    let key = crypto::key_source(&cfg, &http).fetch().await.map_err(|e| format!("encryption key: {e}"))?;
    let store = store::Store::open(&cfg.data_dir, crypto::Vault::new(key))?;
    let listen = cfg.listen;
    let hub = hub::Hub::new(cfg, http, store);
    hub.restore();
    raid::spawn(hub.clone());

    let listener = tokio::net::TcpListener::bind(listen).await.map_err(|e| format!("bind {listen}: {e}"))?;
    info!("kd-pager-hub listening on {listen}");
    axum::serve(listener, api::router(hub)).await.map_err(|e| format!("serve: {e}"))
}

#[tokio::main(flavor = "multi_thread", worker_threads = 2)]
async fn main() {
    if let Err(e) = run().await {
        error!("fatal: {e}");
        std::process::exit(1);
    }
}
