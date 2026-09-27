import { z } from "zod";
import type { ToolDef } from "./tools.js";

const id = z.string().regex(/^[a-z0-9._-]+:[a-z0-9/._-]+$/);
const scalar = {
  name: z.string().min(1).max(64).optional(),
  color: z.string().regex(/^#?([\da-fA-F]{6}|[\da-fA-F]{8})$/).optional(),
  size: z.number().min(0.01).max(8).optional(),
  lifetime: z.number().int().min(1).max(1200).optional(),
  speed: z.number().int().min(0).max(32).optional(),
  rate: z.number().min(0).max(1000).optional(),
  duration: z.number().int().min(1).max(1200).optional(),
  looping: z.boolean().optional(),
  maxParticles: z.number().int().min(1).max(10000).optional(),
};
const offset = { x: z.number().min(-32).max(32).optional(), y: z.number().min(-32).max(32).optional(), z: z.number().min(-32).max(32).optional() };
const emitter = z.object({ ...scalar, ...offset }).strict();
const graphEmitter = z.object({ ...scalar, ...offset, type: z.enum(["particle", "trail", "beam", "empty"]).optional(), config: z.string().max(65536).optional() }).strict();
const position = { x: z.number().finite().optional(), y: z.number().finite().optional(), z: z.number().finite().optional(), vx: z.number().min(-1).max(1).optional(), vy: z.number().min(-1).max(1).optional(), vz: z.number().min(-1).max(1).optional() };
const tool = (name: string, description: string, inputSchema: z.ZodTypeAny, requiresControl = false): ToolDef => ({ name, description, inputSchema, requiresControl });
const replacement = { mode: z.enum(["create", "replace"]).default("create"), expectedSha256: z.string().regex(/^[a-f0-9]{64}$/).optional() };
const binding = { texture: id, graph: z.string().optional(), index: z.number().int().min(0).default(0), columns: z.number().int().min(1).max(32).default(1), rows: z.number().int().min(1).max(32).default(1), frames: z.number().int().min(1).max(256).default(1) };

export const PHOTON_TOOLS: ToolDef[] = [
  tool("photon_import_texture", "Import a bounded RGBA PNG into the managed resource pack. Poll reload before binding.", z.object({ id, png: z.string().max(5592408), ...replacement }), true),
  tool("photon_bind_texture", "Bind a managed texture and optional lifetime atlas animation to a generated emitter.", z.object({ id, ...binding }), true),
  tool("photon_restore_fx", "Restore validated actual runtime SNBT, backing up an existing destination.", z.object({ id, snbt: z.string().max(2097152), ...replacement }), true),
  tool("photon_editor_export", "Back up the active project and export its actual FX to the managed resource pack.", z.object({ id, ...replacement }), true),
  tool("photon_editor_restore", "Restore a full project including its resource library, with active project backup.", z.object({ snbt: z.string().max(2097152) }), true),
  tool("photon_editor_bind_texture", "Bind an imported texture/atlas and register it in the native project material library.", z.object(binding), true),
  tool("photon_capture_start", "Capture uncached world framebuffer PNGs; records actual timing. Maximum 2073600 pixels per frame.", z.object({ frames: z.number().int().min(2).max(120).default(40), fps: z.number().int().min(1).max(10).default(8) }), true),
  tool("photon_capture_status", "Read continuous capture progress and exact frame timestamps/hashes.", z.object({})),
  tool("photon_capture_stop", "Cancel an active capture and preserve completed frames.", z.object({}), true),
  tool("photon_editor_state", "Read the active Photon project as typed SNBT.", z.object({})),
  tool("photon_editor_new", "Back up an existing Photon project and create a native empty project.", z.object({}), true),
  tool("photon_editor_load", "Back up the active project and load a generated effect into the native editor.", z.object({ id }), true),
  tool("photon_editor_select", "Open the native configurator for an emitter in the active project.", z.object({ graph: z.string().optional(), index: z.number().int().min(0) }), true),
  tool("photon_editor_save", "Save and verify an independent timestamped Photon project backup.", z.object({}), true),
  tool("photon_editor_close", "Back up the active Photon project and close the editor safely.", z.object({}), true),
  tool("photon_editor_set", "Update native emitter config/transform/name in the active project, with backup.", z.object({ graph: z.string().optional(), index: z.number().int().min(0), patch: z.string().max(65536) }), true),
  tool("photon_editor_fields", "Read visible native LDLib text/numeric fields and their current IDs.", z.object({})),
  tool("photon_editor_set_text", "Set a field using its native validator and responder. Obtain its ID from photon_editor_fields.", z.object({ field: z.string(), text: z.string().max(4096) }), true),
  tool("photon_status", "Generated pack selection and reload status, not runtime rendering status.", z.object({})),
  tool("photon_errors", "Read the bounded global Photon error journal, including native logged renderer errors. Entries are not attributed to individual handles.", z.object({ after: z.number().int().min(0).optional() })),
  tool("photon_list_fx", "List generated Photon resource IDs.", z.object({})),
  tool("photon_create_fx", "Create a single Photon particle effect; refuses an existing ID.", z.object({ id, ...scalar }).strict(), true),
  tool("photon_update_fx", "Update scalar parameters of a single particle emitter. For pairs use photon_update_pair_fx.", z.object({ id, ...scalar }).strict(), true),
  tool("photon_inspect_fx", "Read the generated single particle parameters.", z.object({ id })),
  tool("photon_create_pair_fx", "Create exactly two independently parameterized particle emitters.", z.object({ id, emitters: z.tuple([emitter, emitter]) }), true),
  tool("photon_update_pair_fx", "Update both particle emitters in an existing pair; preserves omitted fields.", z.object({ id, emitters: z.tuple([emitter, emitter]) }), true),
  tool("photon_inspect_pair_fx", "Read both particle emitters and their local offsets.", z.object({ id })),
  tool("photon_clear_fx_cache", "Clear the Photon parsed FX cache on the client, without stopping runtimes.", z.object({}), true),
  tool("photon_play_fx", "Legacy server-command player attachment; command acceptance does not prove rendering.", z.object({ id, player: z.string().optional() }), true),
  tool("photon_emitter_template", "Obtain native Photon default emitter SNBT before authoring advanced config.", z.object({ type: z.enum(["particle", "trail", "beam", "empty"]) })),
  tool("photon_write_fx", "Create or replace a complete native Photon graph, including trails, beams, curves, materials and named subFXs. config is native SNBT.", z.object({ id, mode: z.enum(["create", "replace"]).default("create"), document: z.object({ emitters: z.array(graphEmitter).min(1).max(32), subFXs: z.record(z.array(graphEmitter).min(1).max(32)).optional() }).strict() }), true),
  tool("photon_read_fx", "Read the complete generated Photon graph as typed SNBT.", z.object({ id })),
  tool("photon_diagnose_fx", "Test active resource discovery, schema, deserialization and runtime construction. Does not emit particles.", z.object({ id })),
  tool("photon_start_fx", "Start a managed local-client effect and return its handle. No multiplayer broadcast. Velocity is blocks per tick.", z.object({ id, ...position, maxTicks: z.number().int().min(1).max(12000).optional() }), true),
  tool("photon_runtime_status", "Query managed runtimes: lifetime, emitter counts and render callbacks. Counts are not visible pixels.", z.object({ handle: z.string().uuid().optional() })),
  tool("photon_stop_fx", "Stop precisely one managed runtime, including observed sub emitters.", z.object({ handle: z.string().uuid() }), true),
  tool("photon_move_fx", "Move a managed runtime or change its velocity; useful for trail authoring.", z.object({ handle: z.string().uuid(), ...position }), true),
  tool("client_resume", "Return from the pause menu; refuses to close an unsaved editor.", z.object({}), true),
];

export const PHOTON_TOOL_NAMES = new Set(PHOTON_TOOLS.map(tool => tool.name));
