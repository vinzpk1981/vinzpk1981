package oauth;

import java.util.List;
import java.util.Map;

//CHANGE : All javax imports need to change to jakarta
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.ModelAndView;

@Controller
public class HelloController {
	// Specifies which Authorization Server is used.
	// Google or Okta (application.properties)
	@Value("${photolibrary.authorizer}")
	private String plAuthorizer;

	// Specifies the album endpoint
	@Value("${photolibrary.albums.uri}")
	private String plAlbumsUri;

	// Specifies the Photos endpoint
	@Value("${photolibrary.photos.uri}")
	private String plPhotosUri;

	// Specfies the global logout endpoint
	@Value("${photolibrary.logout.url}")
	private String plLogoutUri;

	@Autowired
	private OAuth2AuthorizedClientService oauth2CliSrv;

	/*
	 * Display the home page of the application
	 */
	@GetMapping("/")
	ModelAndView showWelcomePage(OAuth2AuthenticationToken authn) {

		OAuth2User principal = authn.getPrincipal();

		// Dump for testing
		System.out.println(principal.getAttributes());

		ModelAndView model = getUserModel(principal);
		model.setViewName("welcome");
		return model;
	}

	/*
	 * Destroy the session and redirect to the authorization server
	 * logout endpoint. This is done so that all sessions are desroyed.
	 */
	@GetMapping("/photolibrary/logout")
	ModelAndView logout(@AuthenticationPrincipal OidcUser user, HttpServletRequest request, OAuth2AuthenticationToken authn) {

		// invalidate the session
		HttpSession session = request.getSession(false);
		if (session != null) {
			session.invalidate();
			System.out.println("> Destroying session. While next login, the request will go to Authorization Server");
		}

		// global logout. The session in Authorization and Authentication
		// Server will be terminated
		System.out.println("> Redirect to the authorization server for a global logout");
		return new ModelAndView("redirect:" + plLogoutUri + "&id_token_hint=" + user.getIdToken().getTokenValue());
	}

	/*
	 * When the user requests to see all albums, then a call is made to the
	 * Resource server to get the information.
	 */
	@GetMapping("/photolibrary/albums")
	ModelAndView retrieveAlbums(HttpServletRequest request, OAuth2AuthenticationToken authn) {

		OAuth2User principal = authn.getPrincipal();
		// System.out.println(principal.getAttributes());

		// Get the Authorized cli .. Very important object
		OAuth2AuthorizedClient client = oauth2CliSrv.loadAuthorizedClient(authn.getAuthorizedClientRegistrationId(),
				authn.getName());

		System.out.println(">>> " + authn.getAuthorizedClientRegistrationId());
		System.out.println(">>> " + authn.getName());

		if (client == null) {
			return destroySessionAndRedirectToHome(request);
		}

		// System.out.println(client.getAccessToken().getTokenValue());

		// Call the resource server
		RestTemplate restTemplate = new RestTemplate();
		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.AUTHORIZATION, "Bearer " + client.getAccessToken().getTokenValue());
		HttpEntity entity = new HttpEntity("", headers);
		ResponseEntity<Map> response = restTemplate.exchange(this.plAlbumsUri, HttpMethod.GET, entity, Map.class);
		System.out.println(response.getBody());

		// Returns a list of Albums
		List<?> albums = (List<?>) response.getBody().get("albums");

		// delegate to the view object for display
		ModelAndView model = getUserModel(principal);
		model.addObject("albums", albums);
		model.setViewName("album-listing");
		return model;
	}

	/*
	 * When the user requests to see all photos of an album, then a call
	 * is made to the Resource server to get the information.
	 */
	@GetMapping("/photolibrary/pics")
	ModelAndView retrievePhotos(HttpServletRequest request, OAuth2AuthenticationToken authn) {

		String albumId = request.getParameter("id");
		System.out.println("> Retrieving photos from " + albumId);

		OAuth2User principal = authn.getPrincipal();
		// System.out.println(principal.getAttributes());

		// Get the Authorized cli .. Very important object
		OAuth2AuthorizedClient client = oauth2CliSrv.loadAuthorizedClient(authn.getAuthorizedClientRegistrationId(),
				authn.getName());
		if (client == null) {
			return destroySessionAndRedirectToHome(request);
		}

		System.out.println(client.getAccessToken().getTokenValue());

		// Call the resource server
		RestTemplate restTemplate = new RestTemplate();
		HttpHeaders headers = new HttpHeaders();
		headers.add(HttpHeaders.AUTHORIZATION, "Bearer " + client.getAccessToken().getTokenValue());
		headers.add(HttpHeaders.CONTENT_TYPE, "application/json");
		String reqBody = String.format("""
				{
					"albumId": "%s"
				}
				""", albumId);
		HttpEntity entity = new HttpEntity(reqBody, headers);
		ResponseEntity<Map> response = restTemplate.exchange(this.plPhotosUri, HttpMethod.POST, entity, Map.class);
		System.out.println(response.getBody());

		// List of photos are obtained from the resource server
		List<?> photos = (List<?>) response.getBody().get("mediaItems");

		// delegates to the view html file for display
		// photos-listing.html
		ModelAndView model = getUserModel(principal);
		model.addObject("photos", photos);
		model.setViewName("photos-listing");
		return model;
	}

	/*
	 * Sends some basic user and setup information to the views
	 * by default. The call can add more properties to it before
	 * passing to the view file.
	 */
	private ModelAndView getUserModel(OAuth2User principal) {
		ModelAndView model = new ModelAndView();
		model.addObject("authorizer", plAuthorizer);
		model.addObject("first", principal.getAttribute("given_name"));
		model.addObject("last", principal.getAttribute("family_name"));
		model.addObject("email", principal.getAttribute("email"));

		// If no picture is retrieved, then set a default picture
		String pic = principal.getAttribute("picture");
		if(pic == null) {
			pic = "/images/person.svg";
		}

		model.addObject("picture", pic);
		return model;
	}

	/*
	 * If you restart the "client", then next request from UI needs to be redirected
	 * to the home page. The Authorization Server session could still be valid and
	 * so, you may not be asked for the credentials. That's okay.
	 */
	private ModelAndView destroySessionAndRedirectToHome(HttpServletRequest request) {

		// invalidate the session
		HttpSession session = request.getSession(false);
		if (session != null) {
			session.invalidate();
			System.out.println("> Destroying session. While next login, the request will go to Authorization Server");
		}

		// redirect to the main page
		return new ModelAndView("redirect:http://localhost:8080/");
	}
}
