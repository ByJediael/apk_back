const express = require("express");
const fs = require("fs");
const path = require("path");
const multer = require("multer");
const { randomUUID } = require("uuid");

const PORT = Number(process.env.PORT || 8080);
const TOKEN = process.env.BACKUP_API_TOKEN || "dev-token-change-me";
const DATA_DIR = path.resolve(process.env.DATA_DIR || path.join(__dirname, "data"));
const JOBS_FILE = path.join(DATA_DIR, "jobs.json");
const UPLOADS_DIR = path.join(DATA_DIR, "uploads");

fs.mkdirSync(DATA_DIR, { recursive: true });
fs.mkdirSync(UPLOADS_DIR, { recursive: true });

if (!fs.existsSync(JOBS_FILE)) {
  fs.writeFileSync(JOBS_FILE, "[]", "utf8");
}

const upload = multer({ dest: UPLOADS_DIR });

const app = express();
app.use(express.json({ limit: "2mb" }));

function readJobs() {
  try {
    return JSON.parse(fs.readFileSync(JOBS_FILE, "utf8"));
  } catch {
    return [];
  }
}

function writeJobs(jobs) {
  fs.writeFileSync(JOBS_FILE, JSON.stringify(jobs, null, 2), "utf8");
}

function auth(req, res, next) {
  const header = req.headers.authorization || "";
  const expected = `Bearer ${TOKEN}`;
  if (header !== expected) {
    return res.status(401).json({ error: "unauthorized" });
  }
  next();
}

app.get("/api/v1/health", auth, (_req, res) => {
  res.json({ status: "ok", service: "folder-backup-api" });
});

app.get("/api/v1/devices/:deviceId/commands", auth, (req, res) => {
  const deviceId = req.params.deviceId;
  const jobs = readJobs();
  const pending = jobs.filter(
    (j) => j.device_id === deviceId && j.status === "pending",
  );

  const commands = pending.map((j) => ({
    id: j.id,
    type: j.type,
    folder_id: j.folder_id || undefined,
    folder_uri: j.folder_uri || undefined,
    absolute_path: j.absolute_path || undefined,
    backup_id: j.backup_id || undefined,
    incremental: j.incremental !== false,
  }));

  const remaining = jobs.filter(
    (j) => !(j.device_id === deviceId && j.status === "pending"),
  );
  const dispatched = pending.map((j) => ({
    ...j,
    status: "dispatched",
    dispatched_at: new Date().toISOString(),
  }));
  writeJobs([...remaining, ...dispatched]);

  res.json({ commands });
});

app.post("/api/v1/upload", auth, upload.single("file"), (req, res) => {
  const meta = {
    job_id: req.body.job_id,
    folder_id: req.body.folder_id,
    relative_path: req.body.relative_path,
    sha256: req.body.sha256,
    size_bytes: req.body.size_bytes,
    last_modified: req.body.last_modified,
    stored_file: req.file?.filename,
    received_at: new Date().toISOString(),
  };

  const logPath = path.join(UPLOADS_DIR, `${meta.job_id || "job"}-manifest.jsonl`);
  fs.appendFileSync(logPath, JSON.stringify(meta) + "\n", "utf8");

  if (req.file && meta.relative_path) {
    const safeName = meta.relative_path.replace(/\.\./g, "_").replace(/\//g, "__");
    const finalPath = path.join(UPLOADS_DIR, meta.job_id || "unknown", safeName);
    fs.mkdirSync(path.dirname(finalPath), { recursive: true });
    fs.renameSync(req.file.path, finalPath);
  }

  res.json({ ok: true });
});

app.post("/api/v1/jobs/:jobId/progress", auth, (req, res) => {
  const jobId = req.params.jobId;
  const jobs = readJobs();
  const idx = jobs.findIndex((j) => j.id === jobId);
  if (idx >= 0) {
    jobs[idx] = {
      ...jobs[idx],
      last_progress: {
        ...req.body,
        at: new Date().toISOString(),
      },
      status:
        req.body.status === "completed"
          ? "completed"
          : req.body.status === "failed"
            ? "failed"
            : jobs[idx].status,
    };
    writeJobs(jobs);
  } else {
    const logPath = path.join(DATA_DIR, "orphan-progress.jsonl");
    fs.appendFileSync(
      logPath,
      JSON.stringify({ job_id: jobId, ...req.body, at: new Date().toISOString() }) +
        "\n",
      "utf8",
    );
  }
  res.json({ ok: true });
});

/** Criar job — use no n8n (HTTP Request) ou curl */
app.post("/api/v1/admin/jobs", auth, (req, res) => {
  const {
    device_id,
    type = "BACKUP",
    folder_id,
    folder_uri,
    absolute_path,
    backup_id,
    incremental = true,
  } = req.body;

  if (!device_id) {
    return res.status(400).json({ error: "device_id obrigatório" });
  }
  if (!["BACKUP", "RESTORE"].includes(type)) {
    return res.status(400).json({ error: "type deve ser BACKUP ou RESTORE" });
  }

  const job = {
    id: `job-${randomUUID().slice(0, 8)}`,
    device_id,
    type,
    folder_id: folder_id || null,
    folder_uri: folder_uri || null,
    absolute_path: absolute_path || null,
    backup_id: backup_id || null,
    incremental: Boolean(incremental),
    status: "pending",
    created_at: new Date().toISOString(),
  };

  const jobs = readJobs();
  jobs.push(job);
  writeJobs(jobs);

  res.status(201).json({ ok: true, job });
});

app.get("/api/v1/admin/jobs", auth, (_req, res) => {
  res.json({ jobs: readJobs() });
});

app.listen(PORT, "0.0.0.0", () => {
  console.log(`folder-backup-api listening on http://0.0.0.0:${PORT}`);
  console.log(`DATA_DIR=${DATA_DIR}`);
  console.log(`Use Authorization: Bearer <BACKUP_API_TOKEN>`);
});
