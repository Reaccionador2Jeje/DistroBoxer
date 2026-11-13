const catalog = document.querySelector("#catalog");
const engineStatus = document.querySelector("#engine-mini");
const search = document.querySelector("#distro-search");
const refreshButton = document.querySelector("#refresh-catalog");
let distributions = [];

const pages = {
  home: ["Tu espacio Linux", "page-home"],
  distros: ["Distribuciones", "page-distros"],
  terminal: ["Terminal", "page-terminal"],
  desktop: ["Escritorio gráfico", "page-desktop"],
  settings: ["Ajustes", "page-settings"],
};

function navigate(page) {
  if (!pages[page]) return;
  document.querySelectorAll(".page").forEach((element) => element.classList.remove("active"));
  document.querySelectorAll(".nav-item").forEach((element) => element.classList.toggle("active", element.dataset.page === page));
  document.getElementById(pages[page][1]).classList.add("active");
  document.querySelector("#page-title").textContent = pages[page][0];
}

document.querySelectorAll("[data-page]").forEach((button) => {
  button.addEventListener("click", () => navigate(button.dataset.page));
});
document.querySelectorAll("[data-go]").forEach((button) => {
  button.addEventListener("click", () => navigate(button.dataset.go));
});

function renderDistro(distro) {
  const card = document.createElement("article");
  card.className = "distro-card";
  const title = document.createElement("h3");
  title.textContent = distro.name;
  const id = document.createElement("span");
  id.className = "distro-id";
  id.textContent = distro.id.toUpperCase();
  const details = document.createElement("p");
  details.textContent = `${distro.package_manager.toUpperCase()} · ${distro.architectures.join(", ")}`;
  const description = document.createElement("p");
  description.textContent = distro.description || "Perfil de distribución Linux.";
  const bootstrap = document.createElement("p");
  bootstrap.className = "muted";
  bootstrap.textContent = `Base: ${distro.bootstrap} · ${distro.archive_format}`;
  const link = document.createElement("a");
  link.href = distro.homepage;
  link.target = "_blank";
  link.rel = "noreferrer";
  link.textContent = "Sitio oficial ↗";
  const badge = document.createElement("span");
  badge.className = "status";
  badge.textContent = distro.id === "alpine" ? "INSTALADOR ANDROID EN PRUEBAS" : "PERFIL · INSTALADOR PENDIENTE";
  card.append(title, id, details, description, bootstrap, link, badge);
  return card;
}

function renderCatalog() {
  const query = (search?.value || "").trim().toLowerCase();
  const filtered = distributions.filter((distro) =>
    [distro.name, distro.id, distro.package_manager, distro.description, distro.bootstrap, distro.archive_format]
      .some((value) => String(value || "").toLowerCase().includes(query))
  );
  catalog.replaceChildren();
  if (filtered.length === 0) {
    const empty = document.createElement("div");
    empty.className = "empty-state";
    empty.textContent = "No se encontraron distribuciones con esa búsqueda.";
    catalog.append(empty);
  } else {
    catalog.append(...filtered.map(renderDistro));
  }
  document.querySelector("#distro-count").textContent = `${filtered.length} de ${distributions.length} perfiles`;
}

search?.addEventListener("input", renderCatalog);

async function loadCatalog() {
  if (refreshButton) {
    refreshButton.disabled = true;
    refreshButton.textContent = "Actualizando…";
  }
  engineStatus.textContent = "Conectando motor…";
  try {
    distributions = await window.__TAURI__.core.invoke("list_distributions");
    renderCatalog();
    document.querySelector("#stat-distros").textContent = String(distributions.length);
    document.querySelector("#stat-engine").textContent = "ONLINE";
    document.querySelector("#settings-engine").textContent = "Conectado · Rust";
    engineStatus.textContent = "Motor Rust conectado";
    engineStatus.parentElement.classList.add("connected");
  } catch (error) {
    document.querySelector("#stat-distros").textContent = "—";
    document.querySelector("#stat-engine").textContent = "OFFLINE";
    document.querySelector("#settings-engine").textContent = "No disponible";
    document.querySelector("#distro-count").textContent = "Catálogo no disponible";
    const message = error instanceof Error ? error.message : String(error);
    const panel = document.createElement("div");
    panel.className = "error-state";
    const title = document.createElement("strong");
    title.textContent = "No se pudo cargar el catálogo";
    const detail = document.createElement("p");
    detail.textContent = message;
    const copy = document.createElement("button");
    copy.type = "button";
    copy.textContent = "Copiar error";
    copy.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(message);
        copy.textContent = "Error copiado";
      } catch {
        copy.textContent = "No se pudo copiar";
      }
    });
    panel.append(title, detail, copy);
    catalog.replaceChildren(panel);
    engineStatus.textContent = "Motor Rust no disponible";
    engineStatus.parentElement.classList.remove("connected");
  } finally {
    if (refreshButton) {
      refreshButton.disabled = false;
      refreshButton.textContent = "↻ Actualizar";
    }
  }
}

refreshButton?.addEventListener("click", loadCatalog);
loadCatalog();
