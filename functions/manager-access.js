const { createHash } = require("node:crypto");

const maxManagerDorms = 20;

function managerEmail(value) {
  const email = typeof value === "string" ? value.trim().toLowerCase() : "";
  return email.length <= 254 && /^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9-]+(?:\.[a-z0-9-]+)+$/.test(email) ? email : "";
}

function managerGrantId(email) {
  return createHash("sha256").update(email).digest("hex");
}

function managerGrantPath(id) {
  return "dormManagerGrants/" + id;
}

function isDormId(id) {
  return typeof id === "string" && /^[A-Za-z0-9_-]{1,80}$/.test(id) && !/^__.*__$/.test(id);
}

function grantedDorms(grant) {
  return Array.isArray(grant?.dormIds) ? grant.dormIds.filter(isDormId).slice(0, maxManagerDorms) : [];
}

module.exports = { managerEmail, managerGrantId, managerGrantPath, isDormId, maxManagerDorms, grantedDorms };
