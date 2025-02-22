/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.fhir.db.impl.dao

import android.util.Log
import ca.uhn.fhir.parser.IParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.fge.jsonpatch.JsonPatch
import com.github.fge.jsonpatch.diff.JsonDiff
import com.google.android.fhir.db.impl.entities.LocalChangeEntity
import java.lang.IllegalArgumentException
import org.hl7.fhir.r4.model.Resource
import org.json.JSONArray
import org.json.JSONObject

object LocalChangeUtils {

  /** Squash the changes by merging them two at a time. */
  fun squash(localChangeEntities: List<LocalChangeEntity>): LocalChangeEntity =
    localChangeEntities.reduce { first, second -> mergeLocalChanges(first, second) }

  fun mergeLocalChanges(first: LocalChangeEntity, second: LocalChangeEntity): LocalChangeEntity {
    val type: LocalChangeEntity.Type
    val payload: String
    when (second.type) {
      LocalChangeEntity.Type.UPDATE ->
        when {
          first.type.equals(LocalChangeEntity.Type.UPDATE) -> {
            type = LocalChangeEntity.Type.UPDATE
            payload = mergePatches(first.payload, second.payload)
          }
          first.type.equals(LocalChangeEntity.Type.INSERT) -> {
            type = LocalChangeEntity.Type.INSERT
            payload = applyPatch(first.payload, second.payload)
          }
          else -> {
            throw IllegalArgumentException(
              "Cannot merge local changes with type ${first.type} and ${second.type}."
            )
          }
        }
      LocalChangeEntity.Type.DELETE -> {
        type = LocalChangeEntity.Type.DELETE
        payload = ""
      }
      LocalChangeEntity.Type.INSERT -> {
        type = LocalChangeEntity.Type.INSERT
        payload = second.payload
      }
    }
    return LocalChangeEntity(
      id = 0,
      resourceId = second.resourceId,
      resourceType = second.resourceType,
      type = type,
      payload = payload
    )
  }

  /** Update a JSON object with a JSON patch (RFC 6902). */
  private fun applyPatch(resourceString: String, patchString: String): String {
    val objectMapper = ObjectMapper()
    val resourceJson = objectMapper.readValue(resourceString, JsonNode::class.java)
    val patchJson = objectMapper.readValue(patchString, JsonPatch::class.java)
    return patchJson.apply(resourceJson).toString()
  }

  /** Merge two JSON patch strings by concatenating their elements into a new JSON array. */
  private fun mergePatches(firstPatch: String, secondPatch: String): String {
    // TODO: validate patches are RFC 6902 compliant JSON patches
    val firstMap = JSONArray(firstPatch).patchMergeMap()
    val secondMap = JSONArray(secondPatch).patchMergeMap()
    firstMap.putAll(secondMap)
    return JSONArray(firstMap.values).toString()
  }

  /** Calculates the JSON patch between two [Resource] s. */
  internal fun diff(parser: IParser, source: Resource, target: Resource): String {
    val objectMapper = ObjectMapper()
    val jsonDiff =
      JsonDiff.asJson(
        objectMapper.readValue(parser.encodeResourceToString(source), JsonNode::class.java),
        objectMapper.readValue(parser.encodeResourceToString(target), JsonNode::class.java)
      )
    if (jsonDiff.size() == 0) {
      Log.i("ResourceDao", "Target ${target.resourceType}/${target.id} is same as source.")
    }
    return jsonDiff.toString()
  }

  /**
   * Creates a mutable map from operation type (e.g. add/remove) + property path to the entire
   * operation containing the updated value. Two such maps can be merged using `Map.putAll()` to
   * yield a minimal set of operations equivalent to individual patches.
   */
  private fun JSONArray.patchMergeMap(): MutableMap<Pair<String, String>, JSONObject> {
    return (0 until this.length())
      .map { this.optJSONObject(it) }
      .associateBy { it.optString("op") to it.optString("path") }
      .toMutableMap()
  }
}
