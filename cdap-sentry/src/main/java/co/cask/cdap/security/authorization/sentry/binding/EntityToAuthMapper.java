/*
 * Copyright © 2016 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.security.authorization.sentry.binding;

import co.cask.cdap.proto.element.EntityType;
import co.cask.cdap.proto.id.EntityId;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.security.authorization.sentry.model.Instance;
import com.google.common.collect.Lists;
import org.apache.sentry.core.common.Authorizable;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts {@link EntityId} to a {@link List} of {@link Authorizable}
 */
public class EntityToAuthMapper {
  public static List<Authorizable> convertEntityToAuthorizable(final String instanceName, final EntityId entityId) {
    List<Authorizable> authorizables = new ArrayList<>();
    authorizables.add(new Instance(instanceName));
    authorizables.add(new Authorizable() {
      @Override
      public String getTypeName() {
        EntityType entityType = entityId.getEntity();
        switch (entityType) {
          case NAMESPACE:
            NamespaceId namespaceId = (NamespaceId) entityId;
            return co.cask.cdap.security.authorization.sentry.model.Authorizable.AuthorizableType.NAMESPACE.name();
        }
        return null;
      }

      @Override
      public String getName() {
        EntityType entityType = entityId.getEntity();
        switch (entityType) {
          case NAMESPACE:
            NamespaceId namespaceId = (NamespaceId) entityId;
            return namespaceId.getNamespace();
        }
        return null;
      }
    });
    return authorizables;
  }
}
