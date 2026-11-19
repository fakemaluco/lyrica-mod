"""Create an empty, disposable 26.3 singleplayer fixture; requires nbtlib.

Example: python scripts/create-world-qa.py --minecraft-jar <26.3.jar> --saves-dir <test-instance/saves>
No existing directory is replaced. Vanilla generates fresh chunks when the fixture first opens.
The format and flat preset come from the supplied official client JAR.
"""

import argparse
import json
from pathlib import Path
import time
import uuid
import zipfile

import nbtlib as nbt


WORLD_NAME = "LyricaWorldQA"
TOP_Y = 80


def tag(value):
    if isinstance(value, bool):
        return nbt.Byte(value)
    if isinstance(value, int):
        return nbt.Int(value)
    if isinstance(value, float):
        return nbt.Double(value)
    if isinstance(value, str):
        return nbt.String(value)
    if isinstance(value, dict):
        return nbt.Compound({key: tag(child) for key, child in value.items()})
    if isinstance(value, list):
        children = [tag(child) for child in value]
        return nbt.List[type(children[0]) if children else nbt.String](children)
    raise TypeError(type(value))


def uuid_tag(player_uuid):
    return nbt.IntArray([int.from_bytes(player_uuid.bytes[index:index + 4], "big", signed=True)
                         for index in range(0, 16, 4)])


def create(minecraft_jar, saves_dir, username, supplied_uuid=None, forest=False):
    saves_dir = saves_dir.resolve()
    if saves_dir.name.lower() != "saves":
        raise ValueError("Choose the disposable test instance's saves directory")
    world_name = "LyricaForestQA" if forest else WORLD_NAME
    destination = saves_dir / world_name
    if destination.exists():
        raise FileExistsError(f"Refusing to overwrite existing world: {destination}")
    with zipfile.ZipFile(minecraft_jar) as jar:
        version = json.loads(jar.read("version.json"))
        if version["id"] != "26.3":
            raise ValueError("This fixture uses the Minecraft 26.3 saved-data format")
        preset = json.loads(jar.read("data/minecraft/worldgen/world_preset/flat.json"))
        dimension_type = json.loads(jar.read("data/minecraft/dimension_type/overworld.json"))
        if forest:
            preset = json.loads(jar.read("data/minecraft/worldgen/world_preset/normal.json"))
    # Standard overworld starts at -64; 145 layers finish at block Y=80, with empty Y=81 above.
    generator = preset["dimensions"]["minecraft:overworld"]["generator"]
    if forest:
        generator["biome_source"] = {"type": "minecraft:fixed", "biome": "minecraft:forest"}
    else:
        generator["settings"].update({"layers": [{"height": 1, "block": "minecraft:bedrock"},
                                                {"height": TOP_Y - dimension_type["min_y"], "block": "minecraft:stone"}],
                                      "features": False, "lakes": False, "structure_overrides": []})
    data_version = version["world_version"]
    # Match the isolated demonstration launcher's existing deterministic test identity.
    player_uuid = uuid.UUID(supplied_uuid) if supplied_uuid else uuid.uuid3(uuid.NAMESPACE_DNS, f"OfflinePlayer:{username}")
    generation = nbt.Compound({"seed": nbt.Long(0), "generate_structures": nbt.Byte(0),
                               "bonus_chest": nbt.Byte(0), "dimensions": tag(preset["dimensions"])})
    player = nbt.Compound({"DataVersion": nbt.Int(data_version), "UUID": uuid_tag(player_uuid),
                           "Pos": nbt.List[nbt.Double]([.5, 81, .5]),
                           "Motion": nbt.List[nbt.Double]([0, 0, 0]),
                           "Rotation": nbt.List[nbt.Float]([0, 0]),
                           "Dimension": nbt.String("minecraft:overworld"), "playerGameType": nbt.Int(3),
                           "Health": nbt.Float(20), "FallDistance": nbt.Float(0),
                           "OnGround": nbt.Byte(0), "foodLevel": nbt.Int(20),
                           "Inventory": nbt.List[nbt.Compound]([])})
    level = nbt.Compound({"DataVersion": nbt.Int(data_version), "LevelName": nbt.String(world_name),
                          "version": nbt.Int(19133), "Version": nbt.Compound({
                              "Name": nbt.String(version["name"]), "Id": nbt.Int(data_version),
                              "Snapshot": nbt.Byte(not version["stable"]), "Series": nbt.String(version["series_id"])}),
                          "GameType": nbt.Int(3), "allowCommands": nbt.Byte(1), "initialized": nbt.Byte(1),
                          "Time": nbt.Long(0), "LastPlayed": nbt.Long(int(time.time() * 1000)),
                          "difficulty_settings": tag({"difficulty": "peaceful", "hardcore": False, "locked": False}),
                          "spawn": nbt.Compound({"dimension": nbt.String("minecraft:overworld"),
                              "pos": nbt.IntArray([0, 81, 0]), "yaw": nbt.Float(0), "pitch": nbt.Float(0)}),
                          "singleplayer_uuid": uuid_tag(player_uuid),
                          "DataPacks": tag({"Enabled": ["vanilla"], "Disabled": []}),
                          "enabled_features": tag(["minecraft:vanilla"]),
                          "WasModded": nbt.Byte(1)})
    rules = tag({"minecraft:respawn_radius": 0, "minecraft:advance_time": False,
                 "minecraft:advance_weather": False, "minecraft:spawn_mobs": False})
    clocks = nbt.Compound({"minecraft:overworld": nbt.Compound({"total_ticks": nbt.Long(6000), "paused": nbt.Byte(1)})})
    weather = tag({"clear_weather_time": 1_000_000, "rain_time": 0, "thunder_time": 0,
                   "raining": False, "thundering": False})
    files = {"level.dat": nbt.Compound({"Data": level}),
             "data/minecraft/world_gen_settings.dat": nbt.Compound({"data": generation}),
             "data/minecraft/game_rules.dat": nbt.Compound({"data": rules}),
             "data/minecraft/world_clocks.dat": nbt.Compound({"data": clocks}),
             "data/minecraft/weather.dat": nbt.Compound({"data": weather}),
             f"playerdata/{player_uuid}.dat": player}
    # Reserve the destination exclusively after all input validation and NBT construction succeeds.
    destination.mkdir(parents=True, exist_ok=False)
    for relative_path, contents in files.items():
        path = destination / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        if relative_path != "level.dat":
            contents["DataVersion"] = nbt.Int(data_version)
        nbt.File(contents).save(path, gzipped=True)
        assert nbt.load(path).snbt() == contents.snbt(), f"NBT round-trip failed: {relative_path}"
    result = {"world": str(destination), "version": version["id"], "dataVersion": data_version,
              "topY": TOP_Y, "spawn": [.5, 81, .5], "gameMode": "spectator",
              "username": username, "uuid": str(player_uuid), "files": len(files)}
    print(json.dumps(result, ensure_ascii=False))
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft-jar", type=Path, required=True)
    parser.add_argument("--saves-dir", type=Path, required=True)
    parser.add_argument("--username", default="LyricaTeste")
    parser.add_argument("--uuid", help="Optional existing isolated test player UUID")
    parser.add_argument("--forest", action="store_true", help="Generate a separate forest with vanilla terrain and trees")
    arguments = parser.parse_args()
    create(arguments.minecraft_jar, arguments.saves_dir, arguments.username, arguments.uuid, arguments.forest)
