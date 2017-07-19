package co.cask.cdap.security.authorization.ranger.binding;

import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.proto.element.EntityType;
import co.cask.cdap.proto.id.ApplicationId;
import co.cask.cdap.proto.id.ArtifactId;
import co.cask.cdap.proto.id.DatasetId;
import co.cask.cdap.proto.id.DatasetModuleId;
import co.cask.cdap.proto.id.DatasetTypeId;
import co.cask.cdap.proto.id.EntityId;
import co.cask.cdap.proto.id.InstanceId;
import co.cask.cdap.proto.id.NamespaceId;
import co.cask.cdap.proto.id.ProgramId;
import co.cask.cdap.proto.id.SecureKeyId;
import co.cask.cdap.proto.id.StreamId;
import co.cask.cdap.proto.security.Action;
import co.cask.cdap.proto.security.Principal;
import co.cask.cdap.proto.security.Privilege;
import co.cask.cdap.proto.security.Role;
import co.cask.cdap.security.spi.authorization.AbstractAuthorizer;
import co.cask.cdap.security.spi.authorization.AuthorizationContext;
import co.cask.cdap.security.spi.authorization.Authorizer;
import co.cask.cdap.security.spi.authorization.UnauthorizedException;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.ranger.audit.provider.MiscUtil;
import org.apache.ranger.plugin.audit.RangerDefaultAuditHandler;
import org.apache.ranger.plugin.policyengine.RangerAccessRequestImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResourceImpl;
import org.apache.ranger.plugin.policyengine.RangerAccessResult;
import org.apache.ranger.plugin.service.RangerBasePlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.Date;
import java.util.Properties;
import java.util.Set;

/**
 * This class implements {@link Authorizer} from CDAP and is responsible for interacting with Ranger to enforece
 * authorization.
 */
public class RangerAuthorizer extends AbstractAuthorizer {
  private static final Logger LOG = LoggerFactory.getLogger(RangerAuthorizer.class);

  private static final String KEY_INSTANCE = "instance";
  private static final String KEY_NAMESPACE = "namespace";
  private static final String KEY_ARTIFACT = "artifact";
  private static final String KEY_APPLICATION = "application";
  private static final String KEY_DATASET = "dataset";
  private static final String KEY_STREAM = "stream";
  private static final String KEY_PROGRAM = "program";
  private static final String KEY_DATASET_MODULE = "dataset_module";
  private static final String KEY_DATASET_TYPE = "dataset_type";
  private static final String KEY_SECUREKEY = "securekey";

  private static final String RESOURCE_SEPARATOR = "#";


  private static volatile RangerBasePlugin rangerPlugin = null;
  private AuthorizationContext context;
  private String instanceName;

  @Override
  public synchronized void initialize(AuthorizationContext context) throws Exception {
    this.context = context;
    Properties properties = context.getExtensionProperties();
    instanceName = properties.containsKey(Constants.INSTANCE_NAME) ?
      properties.getProperty(Constants.INSTANCE_NAME) : "cdap";
    if (rangerPlugin == null) {
      try {
        UserGroupInformation ugi = UserGroupInformation.getLoginUser();
        if (ugi != null) {
          MiscUtil.setUGILoginUser(ugi, null);
        }
        LOG.debug("Initializing Ranger CDAP Plugin with UGI {}", ugi);
      } catch (Throwable t) {
        LOG.error("Error getting principal.", t);
      }
      rangerPlugin = new RangerBasePlugin("cdap", "cdap");
    }
    rangerPlugin.init();
    RangerDefaultAuditHandler auditHandler = new RangerDefaultAuditHandler();
    rangerPlugin.setResultProcessor(auditHandler);
  }


  @Override
  public void enforce(EntityId entity, Principal principal, Action action) throws Exception {
    LOG.info("=====> enforce({}, {}, {})", entity, principal, action);
    LOG.info("Enforce called on entity {}, principal {}, action {}", entity, principal, action);
    if (rangerPlugin == null) {
      LOG.warn("CDAP Ranger Authorizer is not initialized");
      throw new RuntimeException("CDAP Ranger Authorizer is not initialized.");
    }

    String requestingUser = principal.getName();
    String ip = InetAddress.getLocalHost().getHostName();
    java.util.Set<String> userGroups = MiscUtil.getGroupsForRequestUser(requestingUser);
    LOG.info("Requesting user {}, ip {}, requesting user groups {}", requestingUser, ip, userGroups);

    Date eventTime = new Date();
    String accessType = toRangerAccessType(action);

    boolean validationFailed = false;

    RangerAccessRequestImpl rangerRequest = new RangerAccessRequestImpl();
    rangerRequest.setUser(requestingUser);
    rangerRequest.setUserGroups(userGroups);
    rangerRequest.setClientIPAddress(ip);
    rangerRequest.setAccessTime(eventTime);

    RangerAccessResourceImpl rangerResource = new RangerAccessResourceImpl();
    rangerRequest.setResource(rangerResource);
    rangerRequest.setAccessType(accessType);
    rangerRequest.setAction(accessType);
    rangerRequest.setRequestData(entity.toString());

    setAccessResource(entity, rangerResource);

    boolean isAuthorized = false;

    try {
      RangerAccessResult result = rangerPlugin.isAccessAllowed(rangerRequest);
      if (result == null) {
        LOG.info("Ranger Plugin returned null. Returning false");
        isAuthorized = false;
      } else {
        isAuthorized = result.getIsAllowed();
      }
    } catch (Throwable t) {
      LOG.warn("Error while calling isAccessAllowed(). request {}", rangerRequest, t);
      throw t;
    } finally {
      LOG.debug("Ranger Request {}, Returning value {}", rangerRequest, isAuthorized);
    }

    if (!isAuthorized) {
      LOG.info("Unauthorized: Principal {} is unauthorized to perform action {} on entity {}, " +
                 "accessType {}",
               principal, action, entity, accessType);
      throw new UnauthorizedException(principal, action, entity);
    }
    LOG.info("<===== enforce({}, {}, {})", entity, principal, action);
  }

  @Override
  public void enforce(EntityId entityId, Principal principal, Set<Action> set) throws Exception {
    LOG.info("======> enforce({}, {}, {})", entityId, principal, set);
    LOG.info("Enforce called on entity {}, principal {}, actions {}", entityId, principal, set);
    //TODO: Investigate if its possible to make the enforce call with set of actions rather than one by one
    for (Action action : set) {
      LOG.info("Calling enforce on action {}", action);
      enforce(entityId, principal, action);
      LOG.info("Enforce done on action {}", action);
    }
    LOG.info("<====== enforce({}, {}, {})", entityId, principal, set);
  }

  @Override
  public void grant(EntityId entity, Principal principal, java.util.Set<Action> actions) throws Exception {
    LOG.warn("Grant operation not supported by Ranger for CDAP");
  }

  @Override
  public void revoke(EntityId entity, Principal principal, java.util.Set<Action> actions) throws Exception {
    LOG.warn("Revoke operation not supported by Ranger for CDAP");
  }

  @Override
  public void revoke(EntityId entity) throws Exception {
    LOG.warn("Revoke for entity operation not supported by Ranger for CDAP");
  }

  @Override
  public void createRole(Role role) throws Exception {
    LOG.warn("Create role operation not supported by Ranger for CDAP");

  }

  @Override
  public void dropRole(Role role) throws Exception {
    LOG.warn("Drop role operation not supported by Ranger for CDAP");

  }

  @Override
  public void addRoleToPrincipal(Role role, Principal principal) throws Exception {
    LOG.warn("Add role to principal operation not supported by Ranger for CDAP");

  }

  @Override
  public void removeRoleFromPrincipal(Role role, Principal principal) throws Exception {
    LOG.warn("Remove role from principal operation not supported by Ranger for CDAP");

  }

  @Override
  public Set<Role> listRoles(Principal principal) throws Exception {
    LOG.warn("List roles operation not supported by Ranger for CDAP");
    return null;
  }

  @Override
  public Set<Role> listAllRoles() throws Exception {
    LOG.warn("List all roles operation not supported by Ranger for CDAP");
    return null;
  }

  @Override
  public Set<Privilege> listPrivileges(Principal principal) throws Exception {
    LOG.warn("List privileges operation not supported by Ranger for CDAP");
    return null;
  }

  private String getRequestingUser() throws IllegalArgumentException {
    Principal principal = context.getPrincipal();
    return principal.getName();
  }

  private String toRangerAccessType(Action action) {
    return action.toString().toLowerCase();
  }

  /**
   * Sets the access resource appropriately depending on the given entityId
   *
   * @param entityId             the entity which needs to be set to
   * @param rangerAccessResource the {@link RangerAccessResourceImpl} to set the entity values to
   */
  private void setAccessResource(EntityId entityId, RangerAccessResourceImpl rangerAccessResource) {
    EntityType entityType = entityId.getEntityType();
    switch (entityType) {
      case INSTANCE:
        rangerAccessResource.setValue(KEY_INSTANCE, ((InstanceId) entityId).getInstance());
        break;
      case NAMESPACE:
        rangerAccessResource.setValue(KEY_INSTANCE, instanceName);
        rangerAccessResource.setValue(KEY_NAMESPACE, ((NamespaceId) entityId).getNamespace());
        break;
      case ARTIFACT:
        ArtifactId artifactId = (ArtifactId) entityId;
        setAccessResource(artifactId.getParent(), rangerAccessResource);
        rangerAccessResource.setValue(KEY_ARTIFACT, artifactId.getArtifact() + RESOURCE_SEPARATOR +
          artifactId.getVersion());
        break;
      case APPLICATION:
        ApplicationId applicationId = (ApplicationId) entityId;
        setAccessResource(applicationId.getParent(), rangerAccessResource);
        rangerAccessResource.setValue(KEY_APPLICATION, applicationId.getApplication() + RESOURCE_SEPARATOR +
          applicationId.getVersion());
        break;
      case DATASET:
        DatasetId dataset = (DatasetId) entityId;
        setAccessResource(dataset.getParent(), rangerAccessResource);
        rangerAccessResource.setValue(KEY_DATASET, dataset.getDataset());
        break;
      case DATASET_MODULE:
        DatasetModuleId datasetModuleId = (DatasetModuleId) entityId;
        setAccessResource(datasetModuleId.getParent(), rangerAccessResource);
        rangerAccessResource.setValue(KEY_DATASET_MODULE, datasetModuleId.getModule());
        break;
      case DATASET_TYPE:
        DatasetTypeId datasetTypeId = (DatasetTypeId) entityId;
        setAccessResource(datasetTypeId.getParent(), rangerAccessResource);
        rangerAccessResource.setValue(KEY_DATASET_TYPE, datasetTypeId.getType());
        break;
      case STREAM:
        StreamId streamId = (StreamId) entityId;
        setAccessResource(streamId.getParent(), rangerAccessResource);
        rangerAccessResource.setValue(KEY_STREAM, streamId.getStream());
        break;
      case PROGRAM:
        ProgramId programId = (ProgramId) entityId;
        setAccessResource(programId.getParent(), rangerAccessResource);
        rangerAccessResource.setValue(KEY_PROGRAM, programId.getType() + RESOURCE_SEPARATOR + programId.getProgram());
        break;
      case SECUREKEY:
        SecureKeyId secureKeyId = (SecureKeyId) entityId;
        setAccessResource(secureKeyId.getParent(), rangerAccessResource);
        rangerAccessResource.setValue(KEY_SECUREKEY, secureKeyId.getName());
        break;
      default:
        throw new IllegalArgumentException(String.format("The entity %s is of unknown type %s", entityId, entityType));
    }
  }
}