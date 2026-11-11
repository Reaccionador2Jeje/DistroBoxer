const catalog = document.querySelector("#catalog");
const engineStatus = document.querySelector("#engine-mini");
const search = document.querySelector("#distro-search");
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
  card.append(title, id, details, bootstrap, link, badge);
  return card;
}

function renderCatalog() {
  const query = (search?.value || "").trim().toLowerCase();
  const filtered = distributions.filter((distro) =>
    [distro.name, distro.id, distro.package_manager].some((value) => value.toLowerCase().includes(query))
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
  try {
    distributions = await window.__TAURI__.core.invoke("list_distributions");
    renderCatalog();
    populateTerminalDistros();
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
    catalog.innerHTML = '<div class="empty-state">No se pudo conectar con el motor Rust.</div>';
    engineStatus.textContent = "Motor Rust no disponible";
  }
}

loadCatalog();


const terminalOutput = document.querySelector("#terminal-output");
const terminalDistro = document.querySelector("#terminal-distro");
const terminalStart = document.querySelector("#terminal-start");
const terminalStop = document.querySelector("#terminal-stop");
const terminalInput = document.querySelector("#terminal-input");
const terminalForm = document.querySelector("#terminal-form");
const terminalSend = document.querySelector("#terminal-send");
const terminalStatus = document.querySelector("#terminal-status");
const terminalSessionLabel = document.querySelector("#terminal-session-label");
let activeTerminalSession = null;
let terminalUnlistenOutput = null;
let terminalUnlistenExit = null;

function appendTerminal(text) {
  terminalOutput.textContent += text;
  if (terminalOutput.textContent.length > 120000) {
    terminalOutput.textContent = terminalOutput.textContent.slice(-90000);
  }
  terminalOutput.scrollTop = terminalOutput.scrollHeight;
}

function setTerminalState(active, label) {
  terminalStatus.textContent = label;
  terminalSessionLabel.textContent = active ? "● SESIÓN ACTIVA" : "● SIN SESIÓN";
  terminalStart.disabled = active;
  terminalStop.disabled = !active;
  terminalInput.disabled = !active;
  terminalSend.disabled = !active;
  if (active) terminalInput.focus();
}

async function installTerminalListeners() {
  if (!window.__TAURI__?.event) return;
  terminalUnlistenOutput = await window.__TAURI__.event.listen("terminal-output", (event) => {
    if (event.payload.session_id === activeTerminalSession) appendTerminal(event.payload.data);
  });
  terminalUnlistenExit = await window.__TAURI__.event.listen("terminal-exit", (event) => {
    if (event.payload.session_id !== activeTerminalSession) return;
    appendTerminal("\\n[ DistroBoxer: proceso finalizado ]\\n");
    activeTerminalSession = null;
    setTerminalState(false, "SESIÓN FINALIZADA");
  });
}

function populateTerminalDistros() {
  terminalDistro.replaceChildren();
  for (const distro of distributions) {
    const option = document.createElement("option");
    option.value = distro.id;
    option.textContent = distro.name;
    terminalDistro.append(option);
  }
}

terminalStart?.addEventListener("click", async () => {
  try {
    const distroId = terminalDistro.value;
    if (!distroId) throw new Error("No hay una distribución seleccionada.");
    terminalStart.disabled = true;
    terminalStatus.textContent = "INICIANDO…";
    const sessionId = await window.__TAURI__.core.invoke("start_terminal", {
      distroId, cols: 100, rows: 28
    });
    activeTerminalSession = sessionId;
    appendTerminal("\\n[ DistroBoxer: shell iniciada en " + distroId + " · sesión " + sessionId + " ]\\n");
    setTerminalState(true, "CONECTADO · " + distroId.toUpperCase());
  } catch (error) {
    appendTerminal("\\n[ error ] " + String(error) + "\\n");
    setTerminalState(false, "ERROR AL INICIAR");
  }
});

terminalStop?.addEventListener("click", async () => {
  if (activeTerminalSession === null) return;
  const sessionId = activeTerminalSession;
  try {
    await window.__TAURI__.core.invoke("stop_terminal", { sessionId });
    appendTerminal("\\n[ DistroBoxer: sesión detenida ]\\n");
  } catch (error) {
    appendTerminal("\\n[ error al detener ] " + String(error) + "\\n");
  } finally {
    activeTerminalSession = null;
    setTerminalState(false, "SESIÓN DETENIDA");
  }
});

terminalForm?.addEventListener("submit", async (event) => {
  event.preventDefault();
  if (activeTerminalSession === null || !terminalInput.value) return;
  const data = terminalInput.value + "\\n";
  terminalInput.value = "";
  try {
    await window.__TAURI__.core.invoke("write_terminal", {
      sessionId: activeTerminalSession, data
    });
  } catch (error) {
    appendTerminal("\\n[ error de entrada ] " + String(error) + "\\n");
  }
});

terminalInput?.addEventListener("keydown", async (event) => {
  if (event.ctrlKey && event.key.toLowerCase() === "c" && activeTerminalSession !== null) {
    event.preventDefault();
    await window.__TAURI__.core.invoke("write_terminal", {
      sessionId: activeTerminalSession, data: "\\u0003"
    });
  }
});

installTerminalListeners().catch((error) => appendTerminal("\\n[ error de eventos ] " + String(error)));
