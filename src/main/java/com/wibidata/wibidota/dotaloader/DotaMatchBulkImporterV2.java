/**
 * (c) Copyright 2013 WibiData, Inc.
 *
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.wibidata.wibidota.dotaloader;

import java.io.IOException;
import java.util.*;

import com.google.gson.JsonArray;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.kiji.mapreduce.KijiTableContext;
import org.kiji.mapreduce.bulkimport.KijiBulkImporter;
import org.kiji.schema.EntityId;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Bulk-importer to load the information about Dota 2 matches
 *
 * <p>Input files should contain JSON data representing a single match. The JSON
 * is expected to follow the API found at http://dev.dota2.com/showthread.php?t=58317.
 *  The the following exceptions are allowed:
 * - account_id can be null (will be set to -1)
 * - additional_unit may be wrapped in an array of length one
 * - game_mode maybe zero (will be set to GameMode.UNKOWN_ZERO)
 * -
 *
 *
 * <pre>
 * { "user_id" : "0", "play_time" : "1325725200000", "song_id" : "1" }
 * </pre>
 *
 * The bulk-importer expects a text input format:
 *   <li> input keys are the positions (in bytes) of each line in input file;
 *   <li> input values are the lines, as Text instances.
 */
public class DotaMatchBulkImporterV2 extends KijiBulkImporter<LongWritable, Text> {
  private static final Logger LOG = LoggerFactory.getLogger(DotaMatchBulkImporterV2.class);
  /** {@inheritDoc} */

  static final JsonParser PARSER = new JsonParser();

  private static int getNullableInt(JsonElement je, int defaultInt){
    return (je == null ? defaultInt : je.getAsInt());
  }

  // Reads an AbilityUpgrade object from a Map of its fields
  private AbilityUpgrade extractAbility(JsonObject abilityData){
    return AbilityUpgrade.newBuilder()
            .setLevel(abilityData.get("level").getAsInt())
            .setAbilityId(abilityData.get("ability").getAsInt())
            .setTime(abilityData.get("time").getAsInt())
            .build();
  }

  // Reads a list of item_ids from a JSON playerData, assumes
  // the items are encoded as item_0, item_1, ... item_5
  private List<Integer> readItems(JsonObject items){
    final List<Integer> itemIds = new ArrayList<Integer>(6);
    for(int i = 0; i < 6; i++){
      itemIds.add(items.get("item_" + i).getAsInt());
    }
    return itemIds;
  }

  // Reads a Player Object from a map of its fields->values
  private Player extractPlayer(JsonObject playerData){

    Player.Builder builder = Player.newBuilder();

    // Set the abilityUpgrades
    final List<AbilityUpgrade> abilityUpgrades = new ArrayList<AbilityUpgrade>();

    final JsonElement uncastAbilities = playerData.get("ability_upgrades");
    // This can be null (players have no abilities selected yet?) use a 0 length list
    if(uncastAbilities != null){
      for(JsonElement o : uncastAbilities.getAsJsonArray()){
        abilityUpgrades.add(extractAbility(o.getAsJsonObject()));
      }
    }
    builder.setAbilityUpgrades(abilityUpgrades);

    // Set the additionalUnit
    JsonElement additionalUnitsElem = playerData.get("additional_units");
    if(additionalUnitsElem == null){
      builder.setAdditionalUnits(null);
    }  else {
      final JsonObject additionalUnit;
      // This is sometimes contained in a list
      if(additionalUnitsElem.isJsonArray()){
        additionalUnit = additionalUnitsElem.getAsJsonArray().get(0).getAsJsonObject();
      } else {
        additionalUnit = additionalUnitsElem.getAsJsonObject();
      }
      builder.setAdditionalUnits(
          AdditionalUnit.newBuilder()
              .setName(additionalUnit.get("unitname").getAsString())
              .setItemIds(readItems(additionalUnit))
              .build());
    }

    return builder
             .setAccountId(getNullableInt(playerData.get("account_id"), -1))
             .setAssists(playerData.get("assists").getAsInt())
             .setDeaths(playerData.get("deaths").getAsInt())
             .setDenies(playerData.get("denies").getAsInt())
             .setExpPerMinute(playerData.get("xp_per_min").getAsDouble())
             .setHeroId(playerData.get("hero_id").getAsInt())
             .setLastHits(playerData.get("last_hits").getAsInt())
             .setLeaverStatus(getNullableInt(playerData.get("leaver_status"), 0))
             .setLevel(playerData.get("level").getAsInt())
             .setPlayerSlot(playerData.get("player_slot").getAsInt())
             .setTowerDamage(playerData.get("tower_damage").getAsInt())
             .setGoldSpent(playerData.get("gold_spent").getAsInt())
             .setGold(playerData.get("gold").getAsInt())
             .setGoldPerMinute(playerData.get("gold_per_min").getAsDouble())
             .setHeroDamage(playerData.get("hero_damage").getAsInt())
             .setHeroHealing(playerData.get("hero_healing").getAsInt())
             .setKills(playerData.get("kills").getAsInt())
             .setItemIds(readItems(playerData))
             .build();
  }


  @Override
  public void produce(LongWritable filePos, Text line, KijiTableContext context)
      throws IOException {

      try {
          // Parse the JSON and wrap a JSONplayerData over it
          final JsonObject playerData = PARSER.parse(line.toString()).getAsJsonObject();

          // Collect the values we need
          final long matchId = playerData.get("match_id").getAsLong();
          final int gameMode = playerData.get("game_mode").getAsInt();
          final int lobbyType = playerData.get("lobby_type").getAsInt();
          final int direTowers = playerData.get("tower_status_dire").getAsInt();
          final int radiantTowers = playerData.get("tower_status_radiant").getAsInt();
          final int direBarracks = playerData.get("barracks_status_dire").getAsInt();
          final int radiantBarracks = playerData.get("barracks_status_radiant").getAsInt();
          final int cluster = playerData.get("cluster").getAsInt();
          final int season = playerData.get("season").getAsInt();
          final long startTime = playerData.get("start_time").getAsLong();
          final long seqNum = playerData.get("match_seq_num").getAsLong();
          final int leagueId = playerData.get("leagueid").getAsInt();
          final int firstBloodTime = playerData.get("first_blood_time").getAsInt();
          final int negativeVotes = playerData.get("negative_votes").getAsInt();
          final int positiveVotes = playerData.get("positive_votes").getAsInt();
          final int duration = playerData.get("duration").getAsInt();
          final boolean radiantWin = playerData.get("radiant_win").getAsBoolean();

          // Build and parse the player stats
          final List<Player> playerStats = new ArrayList<Player>(10);
          for(JsonElement o : playerData.get("players").getAsJsonArray()){
              playerStats.add(extractPlayer(o.getAsJsonObject()));
          }
          final Players players = Players.newBuilder().setPlayers(playerStats).build();

          // More informative error messages if the modes are out of bounds
          if(lobbyType < -1 || lobbyType > 5){
              throw new RuntimeException("Bad lobby type int: " + lobbyType);
          }
          if(gameMode < 0 || gameMode > 13){
              throw new RuntimeException("Bad game mode int: " + gameMode);
          }

          EntityId eid = context.getEntityId(matchId + "");

          // Produce all our data
          context.put(eid, "data", "match_id", startTime, matchId);
          context.put(eid, "data", "dire_towers_status", startTime, direTowers);
          context.put(eid, "data", "radiant_towers_status", startTime, radiantTowers);
          context.put(eid, "data", "dire_barracks_status", startTime, direBarracks);
          context.put(eid, "data", "radiant_barracks_status", startTime, radiantBarracks);
          context.put(eid, "data", "cluster", startTime, cluster);
          context.put(eid, "data", "season", startTime, season);
          context.put(eid, "data", "start_time", startTime, startTime);
          context.put(eid, "data", "match_seq_num", startTime, seqNum);
          context.put(eid, "data", "league_id", startTime, leagueId);
          context.put(eid, "data", "first_blood_time", startTime, firstBloodTime);
          context.put(eid, "data", "negative_votes", startTime, negativeVotes);
          context.put(eid, "data", "positive_votes", startTime, positiveVotes);
          context.put(eid, "data", "duration", startTime, duration);
          context.put(eid, "data", "radiant_wins", startTime, radiantWin);
          context.put(eid, "data", "player_data", startTime, players);
          context.put(eid, "data", "game_mode", startTime,
                  GameMode.values()[gameMode].toString());
          context.put(eid, "data", "lobby_type", startTime,
                  LobbyType.values()[lobbyType].toString());
      } catch (RuntimeException re){
          // For RunetimeExceptions we try to log additional information debugging purposes
          try {
              LOG.error("Runtime Exception! MatchId=" +
                        "\nLine\n" + line + "\nMessage:\n" + re.toString());
          } catch (RuntimeException ex) {
              LOG.debug("Error loggging the error: " + ex.getMessage());
          }
          throw re;
      }
  }
}
