package fr.ippon.tatami.web.rest;

import com.yammer.metrics.annotation.Timed;
import fr.ippon.tatami.config.GroupRoles;
import fr.ippon.tatami.domain.Group;
import fr.ippon.tatami.domain.User;
import fr.ippon.tatami.security.AuthenticationService;
import fr.ippon.tatami.service.GroupService;
import fr.ippon.tatami.service.SuggestionService;
import fr.ippon.tatami.service.TimelineService;
import fr.ippon.tatami.service.UserService;
import fr.ippon.tatami.service.dto.StatusDTO;
import fr.ippon.tatami.service.dto.UserGroupDTO;
import fr.ippon.tatami.service.util.DomainUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import javax.inject.Inject;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Collection;

/**
 * REST controller for managing groups.
 *
 * @author Julien Dubois
 */
@Controller
public class GroupController {

    private final Logger log = LoggerFactory.getLogger(GroupController.class);

    @Inject
    private TimelineService timelineService;

    @Inject
    private GroupService groupService;

    @Inject
    private AuthenticationService authenticationService;

    @Inject
    private UserService userService;

    @Inject
    private SuggestionService suggestionService;



    /**
     * Get groups of the current user.
     */
    @RequestMapping(value = "/rest/groups",
            method = RequestMethod.GET,
            produces = "application/json")
    @ResponseBody
    @Timed
    public Collection<Group> getGroups() {
        User currentUser = authenticationService.getCurrentUser();
        return groupService.getGroupsForUser(currentUser);
    }


    /**
     * GET  /group/:groupId -> returns the group with the requested id
     */
    @RequestMapping(value = "/rest/groups/{groupId}",
            method = RequestMethod.GET,
            produces = "application/json")
    @ResponseBody
    @Timed
    public Group getGroup(@PathVariable("groupId") String groupId) {
        User currentUser = authenticationService.getCurrentUser();
        String domain = DomainUtil.getDomainFromLogin(currentUser.getLogin());
        Group publicGroup = groupService.getGroupById(domain, groupId);
        if (publicGroup != null && publicGroup.isPublicGroup()) {
            Group result = getGroupFromUser(currentUser, groupId);
            Group groupClone = (Group) publicGroup.clone();
            if (result != null) {
                groupClone.setMember(true);
            }
            if (isGroupManagedByCurrentUser(publicGroup)) {
                groupClone.setAdministrator(true);
            }
            return groupClone;
        } else {
            Group result = getGroupFromUser(currentUser, groupId);
            Group groupClone = null;
            if (result == null ) {
                log.info("Permission denied! User {} tried to access group ID = {} ", currentUser.getLogin(), groupId);
                result =  new Group();
                result.setName(publicGroup.getName());
                result.setGroupId(groupId);
                return result;
            } else if( groupService.getMembersForGroup(groupId,currentUser) == null ){
                return result;
            } else {
                groupClone = (Group) result.clone();
                groupClone.setMember(true);
                if (isGroupManagedByCurrentUser(publicGroup)) {
                    groupClone.setAdministrator(true);
                }
            }
            return groupClone;
        }
    }




    /**
     * PUT  /group/:groupId -> update the group with the requested id
     */
    @RequestMapping(value = "/rest/groups/{groupId}",
            method = RequestMethod.PUT,
            produces = "application/json")
    @ResponseBody
    @Timed
    public Group updateGroup(@PathVariable("groupId") String groupId, @RequestBody Group groupEdit, HttpServletResponse response) {
        Group group = getGroup(groupId);

        if (group != null) {
            if (!isGroupManagedByCurrentUser(group)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return null;
            } else {
                group.setDomain(authenticationService.getCurrentUser().getDomain());
                group.setName(groupEdit.getName());
                group.setDescription(groupEdit.getDescription());
                group.setArchivedGroup(groupEdit.isArchivedGroup());
                groupService.editGroup(group);
                return group;
            }
        } else {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return null;
        }
    }






    @RequestMapping(value = "/rest/groups/{groupId}/timeline",
            method = RequestMethod.GET,
            produces = "application/json")
    @ResponseBody
    @Timed
    public Collection<StatusDTO> listStatusForGroup(@PathVariable(value = "groupId") String groupId,
                                                    @RequestParam(required = false) Integer count,
                                                    @RequestParam(required = false) String start,
                                                    @RequestParam(required = false) String finish) {

        log.debug("REST request to get statuses for group : {}", groupId);
        if (groupId == null) {
            return new ArrayList<StatusDTO>();
        }
        if (count == null) {
            count = 20;
        }
        Group group = this.getGroup(groupId);
        if (group == null || (!group.isPublicGroup() && !group.isMember()) ) {
            return new ArrayList<StatusDTO>();
        } else {
            return timelineService.getGroupline(groupId, count, start, finish);
        }
    }

    /**
     * GET  /groupmemberships/lookup -> return extended data about the user's groups
     */
    @RequestMapping(value = "/rest/groupmemberships/lookup",
            method = RequestMethod.GET,
            produces = "application/json")
    @ResponseBody
    @Timed
    public Collection<Group> getUserGroups(@RequestParam("screen_name") String username) {
        User user = userService.getUserByUsername(username);
        if (user == null) {
            log.debug("Trying to find group for non-existing username = {}", username);
            return new ArrayList<Group>();
        }
        return groupService.getGroupsForUser(user);
    }



    /**
     * Get groups where the current user is admin.
     */
    @RequestMapping(value = "/rest/admin/groups",
            method = RequestMethod.GET,
            produces = "application/json")
    @ResponseBody
    @Timed
    public Collection<Group> getAdminGroups() {
        return groupService.getGroupsWhereCurrentUserIsAdmin();
    }

    /**
     * POST create new group.
     */
    @RequestMapping(value = "/rest/groups",
            method = RequestMethod.POST,
            produces = "application/json")
    @ResponseBody
    @Timed
    public Group createGroup(HttpServletResponse response, @RequestBody Group group) {
        if (group.getName() != null && !group.getName().equals("")) {
            groupService.createGroup(group.getName(), group.getDescription(), group.isPublicGroup());
        } else {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
        return group;
    }

    /**
     * GET  /groupmemberships/suggestions -> suggest groups to join
     */
    @RequestMapping(value = "/rest/groupmemberships/suggestions",
            method = RequestMethod.GET,
            produces = "application/json")
    @ResponseBody
    @Timed
    public Collection<Group> suggestions() {
        String login = authenticationService.getCurrentUser().getLogin();
        return groupService.buildGroupList(suggestionService.suggestGroups(login));
    }


    /**
     * GET  /groups/{groupId}/members/ -> members of the group
     */
    @RequestMapping(value = "/rest/groups/{groupId}/members/",
            method = RequestMethod.GET,
            produces = "application/json")
    @ResponseBody
    @Timed
    public Collection<UserGroupDTO> getGroupsUsers(HttpServletResponse response, @PathVariable("groupId") String groupId) {

        User currentUser = authenticationService.getCurrentUser();
        Group currentGroup = groupService.getGroupById(currentUser.getDomain(), groupId);

        Collection<UserGroupDTO> users = null;

        if (currentUser == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // Authentication required
        } else if (currentGroup == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND); // Resource not found
        } else {
            users = groupService.getMembersForGroup(groupId, currentUser.getLogin());
        }
        return users;
    }

    /**
     * GET  /groups/{groupId}/members/{userUsername} -> get a member to group status
     */
    @RequestMapping(value = "/rest/groups/{groupId}/members/{username}",
            method = RequestMethod.GET,
            produces = "application/json")
    @ResponseBody
    @Timed
    public UserGroupDTO getUserToGroup(HttpServletResponse response, @PathVariable("groupId") String groupId, @PathVariable("username") String username) {

        User currentUser = authenticationService.getCurrentUser();
        Group currentGroup = groupService.getGroupById(currentUser.getDomain(), groupId);

        Collection<UserGroupDTO> users = null;

        if (currentUser == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // Authentication required
        } else if (currentGroup == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND); // Resource not found
        } else {
            users = groupService.getMembersForGroup(groupId, currentUser.getLogin());
        }

        for (UserGroupDTO user : users) {
            if (user.getLogin().equals(currentUser.getLogin())) {
                return user;
            }
        }

        UserGroupDTO currentUserDTO = new UserGroupDTO();
        currentUserDTO.setLogin(currentUser.getLogin());
        currentUserDTO.setUsername(currentUser.getUsername());
        currentUserDTO.setAvatar(currentUser.getAvatar());
        currentUserDTO.setFirstName(currentUser.getFirstName());
        currentUserDTO.setLastName(currentUser.getLastName());
        currentUserDTO.setIsMember(false);

        return currentUserDTO;
    }

    /**
     * PUT  /groups/{groupId}/members/{userUsername} -> add a member to group
     */
    @RequestMapping(value = "/rest/groups/{groupId}/members/{username}",
            method = RequestMethod.PUT,
            produces = "application/json")
    @ResponseBody
    @Timed
    public UserGroupDTO addUserToGroup(HttpServletResponse response, @PathVariable("groupId") String groupId, @PathVariable("username") String username) {

        User currentUser = authenticationService.getCurrentUser();
        Group currentGroup = groupService.getGroupById(currentUser.getDomain(), groupId);
        User userToAdd = userService.getUserByUsername(username);

        UserGroupDTO dto = null;

        if (currentUser == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // Authentication required
        } else if (currentGroup == null || userToAdd == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND); // Resource not found
        } else {
            if (isGroupManagedByCurrentUser(currentGroup) && !currentUser.equals(userToAdd)) {
                groupService.addMemberToGroup(userToAdd, currentGroup);
                dto = groupService.getMembersForGroup(groupId, userToAdd);
            } else if (currentGroup.isPublicGroup() && currentUser.equals(userToAdd) && !isGroupManagedByCurrentUser(currentGroup)) {
                groupService.addMemberToGroup(userToAdd, currentGroup);
                dto = groupService.getMembersForGroup(groupId, userToAdd);
            } else if(! currentGroup.isPublicGroup() && currentUser.equals(userToAdd) && !isGroupManagedByCurrentUser(currentGroup)) {
            	groupService.requestToJoin(userToAdd, currentGroup);
            	dto = groupService.getMembersForGroup(groupId, userToAdd);
            } else {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            }
        }
        return dto;
    }

    /**
     * DELETE  /groups/{groupId}/members/{userUsername} -> remove a member to group
     */
    @RequestMapping(value = "/rest/groups/{groupId}/members/{username}",
            method = RequestMethod.DELETE,
            produces = "application/json")
    @ResponseBody
    @Timed
    public boolean removeUserFromGroup(HttpServletResponse response, @PathVariable("groupId") String groupId, @PathVariable("username") String username) {

        User currentUser = authenticationService.getCurrentUser();
        Group currentGroup = groupService.getGroupById(currentUser.getDomain(), groupId);
        User userToremove = userService.getUserByUsername(username);

        UserGroupDTO dto = null;

        if (currentUser == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // Authentication required
            return false;
        } else if (currentGroup == null || userToremove == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND); // Resource not found
            return false;
        } else {
            if (isGroupManagedByCurrentUser(currentGroup) && !currentUser.equals(userToremove)) {
                groupService.removeMemberFromGroup(userToremove, currentGroup);
                groupService.getMembersForGroup(groupId, userToremove);
            }
            //A user can leave a group, even if the group is private.
            else if (currentUser.equals(userToremove) && !isGroupManagedByCurrentUser(currentGroup)) {
                groupService.removeMemberFromGroup(userToremove, currentGroup);
                groupService.getMembersForGroup(groupId, userToremove);
            } else {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return false;
            }
        }
        return true;
    }
    
    /**
     * PUT /groups/{groupId}/requests/{userLogin} -> accept a join request 
     */
    @RequestMapping(value = "/rest/groups/{groupId}/requests/{username}",
    		method = RequestMethod.PUT,
    		produces = "application/json")
    @ResponseBody
    @Timed
    public boolean acceptJoinRequest(HttpServletResponse response, @PathVariable("groupId") String groupId, @PathVariable("username") String username) {
    	
    	User currentUser = authenticationService.getCurrentUser();
    	Group currentGroup = groupService.getGroupById(currentUser.getDomain(), groupId);
    	User userToAccept = userService.getUserByUsername(username);
    	
    	log.debug("CurrentUser : {}", currentUser);
    	
    	log.debug("UserToAccept : {}", userToAccept);
    	
    	if (currentUser == null) {
    		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // Authentication required
    		return false;
    	} else if (currentGroup == null || userToAccept == null) {
    		response.setStatus(HttpServletResponse.SC_NOT_FOUND);
    		return false;
    	} else {
    		if ( !currentGroup.isPublicGroup() && isGroupManagedByCurrentUser(currentGroup) && !currentUser.equals(userToAccept)) {
    			groupService.acceptRequestToJoin(userToAccept, currentGroup);
    		} else {
    			response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    			log.debug("Error : {} {} {}",  !currentGroup.isPublicGroup(), isGroupManagedByCurrentUser(currentGroup), !currentUser.equals(userToAccept));
    			return false;
    		}
    	}
    	return true;
    }
    
    /**
     * DELETE /groups/{groupId}/requests/{userLogin} -> accept a join request 
     */
    @RequestMapping(value = "/rest/groups/{groupId}/requests/{username}",
    		method = RequestMethod.DELETE,
    		produces = "application/json")
    @ResponseBody
    @Timed
    public boolean rejectJoinRequest(HttpServletResponse response, @PathVariable("groupId") String groupId, @PathVariable("username") String username) {
    	
    	User currentUser = authenticationService.getCurrentUser();
    	Group currentGroup = groupService.getGroupById(currentUser.getDomain(), groupId);
    	User userToAccept = userService.getUserByUsername(username);
    	
    	if (currentUser == null) {
    		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // Authentication required
    		return false;
    	} else if (currentGroup == null || userToAccept == null) {
    		response.setStatus(HttpServletResponse.SC_NOT_FOUND);
    		return false;
    	} else {
    		if ( !currentGroup.isPublicGroup() && isGroupManagedByCurrentUser(currentGroup) && !currentUser.equals(userToAccept)) {
    			groupService.rejectRequestToJoin(userToAccept, currentGroup);
    		} else {
    			response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    			return false;
    		}
    	}
    	return true;
    }

    private boolean isGroupManagedByCurrentUser(Group group) {
        Collection<Group> groups = groupService.getGroupsWhereCurrentUserIsAdmin();
        boolean isGroupManagedByCurrentUser = false;
        for (Group testGroup : groups) {
            if (testGroup.getGroupId().equals(group.getGroupId())) {
                isGroupManagedByCurrentUser = true;
                break;
            }
        }
        return isGroupManagedByCurrentUser;
    }

    private Group getGroupFromUser(User currentUser, String groupId) {
        Collection<Group> groups = groupService.getGroupsForUser(currentUser);
        for (Group testGroup : groups) {
            if (testGroup.getGroupId().equals(groupId)) {
                return testGroup;
            }
        }
        return null;
    }
}
