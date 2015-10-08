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

import com.firebase.client.AuthData;
import com.firebase.client.Firebase;
import com.firebase.client.FirebaseException;

/**
 * Dummy authenticator for test instances of {@link Firebase}.
 */
public class FirebaseEmptyAuthenticator extends FirebaseAuthenticator {

  private static final long serialVersionUID = 7664558699766082532L;

  /* (non-Javadoc)
   * @see utils.FirebaseAuthenticator#authenticate(com.firebase.client.Firebase)
   */
  @Override
  public AuthData authenticate(Firebase firebase) throws FirebaseException {
    return new AuthData(null, 0, null, null, null, null);
  }
}
