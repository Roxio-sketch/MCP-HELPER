import { afterEach, describe, expect, it, vi } from "vitest";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { createMcpServer } from "../src/mcp/transport.js";
import type { ModClient } from "../src/api/modClient.js";

describe("Photon MCP protocol", () => {
  const close: Array<() => Promise<void>> = [];
  afterEach(async () => { for (const fn of close.splice(0)) await fn(); });
  async function connect(result: unknown = { ok: true }) {
    const sendCommand = vi.fn().mockResolvedValue(result);
    const mod = { connected: true, sendCommand } as unknown as ModClient;
    const server = createMcpServer(mod);
    const client = new Client({ name: "photon-test", version: "1" });
    const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
    await server.connect(serverTransport); await client.connect(clientTransport);
    close.push(() => client.close(), () => server.close());
    return { client, sendCommand };
  }
  it("advertises real schemas and forwards a nested graph without flattening", async () => {
    const { client, sendCommand } = await connect();
    const { tools } = await client.listTools();
    const schema = tools.find(t => t.name === "photon_write_fx")!.inputSchema;
    expect(schema.properties).toHaveProperty("document");
    const document = { emitters: [{ type: "trail", config: "{time:40}" }] };
    await client.callTool({ name: "photon_write_fx", arguments: { id: "test:trail", document } });
    expect(sendCommand).toHaveBeenCalledWith("photon_write_fx", { id: "test:trail", mode: "create", document });
  });
  it("rejects an invalid pair before reaching the game", async () => {
    const { client, sendCommand } = await connect();
    const response = await client.callTool({ name: "photon_update_pair_fx", arguments: { id: "test:pair", emitters: [{}] } });
    expect(response.isError).toBe(true); expect(sendCommand).not.toHaveBeenCalled();
  });
  it("preserves client diagnostic failures as MCP errors", async () => {
    const { client } = await connect({ ok: false, stage: "resource_discovery", error: "missing FX" });
    const response = await client.callTool({ name: "photon_diagnose_fx", arguments: { id: "test:missing" } });
    expect(response.isError).toBe(true);
    expect(JSON.stringify(response.content)).toContain("resource_discovery");
  });
  it("passes atlas layout and conflict hashes without loss", async () => {
    const { client, sendCommand } = await connect();
    await client.callTool({ name: "photon_editor_bind_texture", arguments: { texture: "test:atlas", columns: 2, rows: 2, frames: 4 } });
    expect(sendCommand).toHaveBeenCalledWith("photon_editor_bind_texture", { texture: "test:atlas", columns: 2, rows: 2, frames: 4, index: 0 });
    await client.callTool({ name: "photon_restore_fx", arguments: { id: "test:atlas", snbt: "{fx:{}}", mode: "replace", expectedSha256: "a".repeat(64) } });
    expect(sendCommand).toHaveBeenLastCalledWith("photon_restore_fx", { id: "test:atlas", snbt: "{fx:{}}", mode: "replace", expectedSha256: "a".repeat(64) });
  });
  it("rejects unbounded capture and forwards status as read-only", async () => {
    const { client, sendCommand } = await connect();
    const response = await client.callTool({ name: "photon_capture_start", arguments: { frames: 121, fps: 60 } });
    expect(response.isError).toBe(true); expect(sendCommand).not.toHaveBeenCalled();
    await client.callTool({ name: "photon_capture_status", arguments: {} });
    expect(sendCommand).toHaveBeenCalledWith("photon_capture_status", {});
  });
});
