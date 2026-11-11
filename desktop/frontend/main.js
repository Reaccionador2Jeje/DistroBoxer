const catalog = document.querySelector("#catalog");
const status = document.querySelector("#engine-status");
const count = document.querySelector("#distro-count");

function renderDistro(distro) {
  const card = document.createElement("article");
  card.className = "distro-card";
  const title = document.createElement("h3");
  title.textContent = distro.name;
  const id = document.createElement("span");
  id.className = "distro-id";
  id.textContent = distro.id;
  const details = document.createElement("p");
  details.textContent = `${distro.package_manager.toUpperCase()} · ${distro.architectures.join(", ")}`;
  const bootstrap = document.createElement("p");
  bootstrap.className = "muted";
  bootstrap.textContent = `Bootstrap: ${distro.bootstrap}`;
  const link = document.createElement("a");
  link.href = distro.homepage;
  link.target = "_blank";
  link.rel = "noreferrer";
  link.textContent = "Official website ↗";
  const badge = document.createElement("span");
  badge.className = "status";
  badge.textContent = "Installer in development";
  card.append(title, id, details, bootstrap, link, badge);
  return card;
}

async function loadCatalog() {
  try {
    const distros = await window.__TAURI__.core.invoke("list_distributions");
    catalog.replaceChildren(...distros.map(renderDistro));
    count.textContent = `${distros.length} profiles`;
    status.textContent = "Rust engine connected · catalog loaded natively";
    status.classList.add("connected");
  } catch (error) {
    status.textContent = `Could not connect to the Rust engine: ${String(error)}`;
    count.textContent = "Unavailable";
  }
}

loadCatalog();
