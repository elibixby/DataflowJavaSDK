/**
 * Copyright (c) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not  use this file except  in compliance with the License. You may obtain
 * a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package utils;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.joda.time.Instant;

import events.FirebaseEvent;

/**
 * Wrapper for a {@link FirebaseEvent} and a {@link Instant}.
 * @param <T> @see FirebaseDataEvent
 */
public class Record<T> implements Comparable<Record<T>>{

  public final Instant timestamp;
  public final FirebaseEvent<T> data;

  @JsonCreator
  public Record(
      @JsonProperty("timestamp") Instant timestamp,
      @JsonProperty("event") FirebaseEvent<T> event){
    this.timestamp = timestamp;
    this.data = event;
  }

  /* (non-Javadoc)
   * @see java.lang.Comparable#compareTo(java.lang.Object)
   */
  @Override
  public int compareTo(Record<T> o) {
    return this.timestamp.compareTo(o.timestamp);
  }
}
