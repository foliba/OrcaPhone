#!/usr/bin/env python3
"""
Builds slicer-core/src/main/resources/orca/system_presets.json.gz from OrcaSlicer's
resources/profiles directory.

OrcaSlicer system presets inherit from each other ("inherits"). Synced cloud presets only store
the values that differ from their system parent, so OrcaPhone needs the *resolved* system presets
to interpret them. This script resolves inheritance and keeps only the settings OrcaPhone maps.

Usage: tools/gen_orca_catalog.py <OrcaSlicer>/resources/profiles [output.json.gz]
"""
import glob
import gzip
import json
import os
import sys

PRINTER_KEYS = [
    "printable_area", "printable_height", "nozzle_diameter", "gcode_flavor",
    "machine_max_speed_x", "machine_max_speed_z", "machine_max_acceleration_extruding",
    "machine_max_acceleration_x", "machine_max_jerk_x", "retraction_length", "retraction_speed",
    "deretraction_speed", "retraction_minimum_travel", "z_hop", "use_firmware_retraction",
    "min_layer_height", "max_layer_height", "machine_start_gcode", "machine_end_gcode",
    "layer_change_gcode", "printer_model", "default_print_profile", "default_filament_profile",
]
FILAMENT_KEYS = [
    "filament_type", "filament_colour", "filament_diameter", "filament_density", "filament_cost",
    "filament_flow_ratio", "nozzle_temperature", "nozzle_temperature_initial_layer",
    "hot_plate_temp", "hot_plate_temp_initial_layer", "textured_plate_temp",
    "textured_plate_temp_initial_layer", "filament_max_volumetric_speed", "fan_min_speed",
    "fan_max_speed", "close_fan_the_first_x_layers", "fan_cooling_layer_time",
    "slow_down_layer_time", "slow_down_min_speed", "filament_retraction_length", "filament_vendor",
]
PROCESS_KEYS = [
    "layer_height", "initial_layer_print_height", "line_width", "outer_wall_line_width",
    "initial_layer_line_width", "seam_position", "elefant_foot_compensation", "ironing_type",
    "ironing_spacing", "ironing_flow", "ironing_speed", "wall_loops", "wall_sequence",
    "top_shell_layers", "bottom_shell_layers", "sparse_infill_density", "sparse_infill_pattern",
    "top_surface_pattern", "bottom_surface_pattern", "infill_wall_overlap", "infill_direction",
    "solid_infill_direction", "initial_layer_speed", "initial_layer_infill_speed",
    "outer_wall_speed", "inner_wall_speed", "sparse_infill_speed", "internal_solid_infill_speed",
    "top_surface_speed", "support_speed", "support_interface_speed", "travel_speed",
    "default_acceleration", "outer_wall_acceleration", "top_surface_acceleration",
    "initial_layer_acceleration", "travel_acceleration", "enable_support",
    "support_type", "tree_support_branch_angle", "tree_support_branch_diameter",
    "support_threshold_angle", "support_on_build_plate_only", "support_top_z_distance",
    "support_object_xy_distance", "support_base_pattern_spacing", "support_interface_top_layers",
    "support_interface_spacing", "skirt_loops", "skirt_distance", "skirt_height", "brim_type",
    "brim_width", "brim_object_gap", "spiral_mode",
]
TYPES = {"machine": ("printer", PRINTER_KEYS), "filament": ("filament", FILAMENT_KEYS), "process": ("print", PROCESS_KEYS)}


def flatten(key, v):
    """Orca JSON values are strings or lists of strings; reduce to the serialized string form."""
    if isinstance(v, list):
        if key == "printable_area":
            return ",".join(str(x) for x in v)
        return str(v[0]) if v else ""
    return str(v)


def main():
    root = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
        os.path.dirname(__file__), "..", "slicer-core", "src", "main", "resources", "orca", "system_presets.json.gz")
    # raw[type][vendor][name] = dict
    raw = {t: {} for t in TYPES}
    for vendor_dir in sorted(glob.glob(os.path.join(root, "*"))):
        if not os.path.isdir(vendor_dir):
            continue
        vendor = os.path.basename(vendor_dir)
        for sub in TYPES:
            for f in sorted(glob.glob(os.path.join(vendor_dir, sub, "**", "*.json"), recursive=True)):
                try:
                    with open(f, encoding="utf-8") as fh:
                        d = json.load(fh)
                except (ValueError, OSError):
                    continue
                name = d.get("name")
                if name:
                    raw[sub].setdefault(vendor, {})[name] = d

    result = {}
    for sub, (iot_type, keys) in TYPES.items():
        by_vendor = raw[sub]
        global_index = {}
        for vendor, items in by_vendor.items():
            for name, d in items.items():
                global_index.setdefault(name, d)
        cache = {}

        def resolve(vendor, name, depth=0):
            key = (vendor, name)
            if key in cache:
                return cache[key]
            d = by_vendor.get(vendor, {}).get(name) or global_index.get(name)
            if d is None or depth > 20:
                return {}
            parent = d.get("inherits")
            values = dict(resolve(vendor, parent, depth + 1)) if parent else {}
            for k, v in d.items():
                if k in keys:
                    values[k] = flatten(k, v)
            cache[key] = values
            return values

        out_type = {}
        for vendor, items in sorted(by_vendor.items()):
            for name, d in sorted(items.items()):
                if str(d.get("instantiation", "true")).lower() != "true":
                    continue
                values = resolve(vendor, name)
                entry = {"vendor": vendor}
                entry.update(values)
                out_type[name] = entry
        result[iot_type] = out_type
        print(f"{iot_type}: {len(out_type)} presets", file=sys.stderr)

    data = json.dumps(result, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with gzip.GzipFile(out, "wb", compresslevel=9, mtime=0) as gz:
        gz.write(data)
    print(f"wrote {out}: {len(data) / 1e6:.1f} MB raw, {os.path.getsize(out) / 1e3:.0f} KB gzipped", file=sys.stderr)


if __name__ == "__main__":
    main()
