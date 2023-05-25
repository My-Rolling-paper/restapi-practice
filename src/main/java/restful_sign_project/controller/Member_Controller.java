package restful_sign_project.controller;

import com.fasterxml.jackson.databind.JsonSerializer;
import lombok.extern.slf4j.Slf4j;

import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.core.parameters.P;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import restful_sign_project.JWT.JwtTokenProvider;
import restful_sign_project.JWT.refresh.RefreshTokenRedisRepository;
import restful_sign_project.controller.Request.RefreshTokenRequest;
import restful_sign_project.controller.Response.*;
import restful_sign_project.controller.status.ResponseMessage;
import restful_sign_project.controller.status.StatusCode;
import restful_sign_project.dto.Member_Dto;
import restful_sign_project.entity.Member;
import restful_sign_project.repository.Member_Repository;
import restful_sign_project.service.Member_Service;
import restful_sign_project.service.PageService;
import restful_sign_project.service.RedisService;

import java.util.*;

import static io.lettuce.core.GeoArgs.Sort.none;

@RestController("/api")
@Slf4j
@Transactional
@EnableGlobalMethodSecurity(prePostEnabled = true)
@CrossOrigin(origins = "https://restful-jwt-project.herokuapp.com")

public class Member_Controller {
    private final BCryptPasswordEncoder encoder;
    private final Member_Service memberService;
    private final JwtTokenProvider jwtTokenProvider;
    private final RedisService redisService;
    private final RefreshTokenRedisRepository refreshTokenRedisRepository;
    private final PageService pageService;
    private final Member_Repository memberRepository;
    @Value("${jwt.token.secret}")
    private String key;

    private final Long expireTimeMs = 30000L;
    private final Long RefreshExpireTimeMs = 1000 * 60 * 60 * 60L;

    public Member_Controller(
            BCryptPasswordEncoder encoder,
            Member_Service memberService,
            JwtTokenProvider jwtTokenProvider,
            RedisService redisService,
            RefreshTokenRedisRepository refreshTokenRedisRepository,
            PageService pageService,
            Member_Repository memberRepository) {
        this.encoder = encoder;
        this.memberService = memberService;
        this.jwtTokenProvider = jwtTokenProvider;
        this.redisService = redisService;
        this.refreshTokenRedisRepository = refreshTokenRedisRepository;
        this.pageService = pageService;
        this.memberRepository = memberRepository;
    }
    //회원가입

    /**
     * JSON형식으로 입력을 받으며 STRING : STRING 형식으로 입력을 받기 때문에 Map함수를 사용함.
     */
    @PostMapping("/signup")
    public ResponseEntity<SignInResponse> signup(@RequestBody Map<String, String> memberDto) {
        SignInResponse response = new SignInResponse();
        String name = (String) memberDto.get("name");
        String email = (String) memberDto.get("email");
        String password = (String) memberDto.get("password");
        Member_Dto member_dto = new Member_Dto(name, email, password);
        Optional<Member> memberFind = memberService.findMemberByEmail(email);
        if (memberFind.isEmpty()) { //Optional로 받았기 때문에 member가 없는/있는 상황을 고려해야함.
            Member member = memberService.join(member_dto);
            log.info(member.getRoles().get(0));
            response = SignInResponse.builder()
                    .code(StatusCode.OK)
                    .message(ResponseMessage.SIGNIN_SUCCESS)
                    .data(member)
                    .build();
            return ResponseEntity.ok(response); // 성공하면 OK안에 response를 담아서 return 함.
        }
        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST); //실패하면 BAD_REQUEST와 함께 response를 보냄
    }

    // 로그인
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody Map<String, String> user) { //로그인도 회원가입과 마찬가지로 map함수를 사용해서 받음
        LoginResponse loginResponse = new LoginResponse();
        Optional<Member> member1 = memberService.findMemberByEmail(user.get("email")); //memberService를 이용해서 email로 Member를 찾음
        if (!member1.isPresent()) { //email로 찾았는데 member가 없는 경우
            loginResponse = LoginResponse.builder()
                    .code(StatusCode.UNAUTHORIZED)
                    .message(ResponseMessage.EMAIL_NOT_FOUND)
                    .build();
            return new ResponseEntity<>(loginResponse, HttpStatus.BAD_REQUEST);
        }
        Member member = member1.get(); //member가 있는 경우
        log.info(member.getPassword());
        log.info(user.get("password"));
        if (!encoder.matches(user.get("password"), member.getPassword())) { //
            loginResponse = LoginResponse.builder()
                    .code(StatusCode.FORBIDDEN)
                    .message(ResponseMessage.PASSWORD_ERROR)
                    .build();
            return new ResponseEntity<>(loginResponse, HttpStatus.BAD_REQUEST);
        }
        long currentTimeMillis = System.currentTimeMillis();
        Long expireTimesEND = expireTimeMs + currentTimeMillis; // Spring에서 현재시간에서 expireTimeMs가 더해진 시간을 MS단위로 보낸다
        log.info(expireTimesEND.toString());

        String token = jwtTokenProvider.createToken(member.getEmail(), member.getRoles(), expireTimeMs); //AccessToken : tokenProvider을 통해서 인자로 이메일,역할,시간을 보낸다.
        String refreshToken = jwtTokenProvider.createToken(member.getEmail(), member.getRoles(), RefreshExpireTimeMs); //RefreshToken : tokenProvider을 통해서 인자로 이메일,역할,시간을 보낸다.
        redisService.setValues(refreshToken, member.getEmail());
        log.info(token);
        log.info(refreshToken);
        //HTTPONLY 쿠키에 RefreshToken 생성후 전달
        ResponseCookie responseCookie =
                ResponseCookie.from("refreshToken", refreshToken)
                        .domain("restful-jwt-project.herokuapp.com")
                        .httpOnly(true)
                        .secure(true)
                        .sameSite("None")
                        .path("/")
                        .maxAge(3600000)
                        .build();

        loginResponse = LoginResponse.builder()
                .code(StatusCode.OK)
                .message(ResponseMessage.LOGIN_SUCCESS)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, responseCookie.toString())
                .header("accessToken", token)
                .header("expireTime", String.valueOf(expireTimesEND))
                .body(loginResponse);
    }

    @PostMapping("/refresh-token")
    public ResponseEntity<RefreshTokenResponse> refreshToken(@RequestBody RefreshTokenRequest refreshTokenRequest) {
        // 요청에서 refresh token 값을 추출
        String refreshToken = refreshTokenRequest.getRefreshToken();

        // Access Token 갱신
        String newAccessToken = jwtTokenProvider.refreshToken(refreshToken);

        long currentTimeMillis = System.currentTimeMillis();
        Long expireTimesEND = expireTimeMs + currentTimeMillis; // Spring에서 현재시간에서 expireTimeMs가 더해진 시간을 MS단위로 보낸다
        log.info(expireTimesEND.toString());

        // 새로운 Access Token 값과 함께 응답 객체 생성
        RefreshTokenResponse response = RefreshTokenResponse.builder()
                .code(StatusCode.OK)
                .message(ResponseMessage.REFRESH_TOKEN_SUCCESS)
                .expireTimeMs(expireTimesEND)
                .build();

        return ResponseEntity.ok().
                header("accessToken", newAccessToken)
                .header("expireTime", String.valueOf(expireTimesEND))
                .body(response);
    }


    @GetMapping("/PasswordChange") // AccessToken이 있다면 정상적으로 접근 가능
    public ResponseEntity<?> passWordChange(HttpServletRequest request) {
        String token = jwtTokenProvider.resolveToken(request);
        ResponseEntity<?> result = pageService.findPageByToken(token);
        return result;
    }

    @PostMapping("/passwordChange/{id}")
    public ResponseEntity<?> passWordChange(@PathVariable Long id, @RequestBody Map<String, String> password) {
        String currentPassword = password.get("currentPassword");
        String newPassWord = password.get("newPassword");
        PasswordChangeResponse passwordChangeResponse = new PasswordChangeResponse();
        // ID를 기반으로 데이터베이스에서 해당 멤버를 조회합니다.
        Optional<Member> op_member = memberRepository.findMemberById(id);
        Member member = op_member.get();
        if (!currentPassword.equals(newPassWord)) {
            if (encoder.matches(currentPassword, member.getPassword())) {
                log.info("비밀번호 똑같아요!!");
                BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
                String bcry_password = passwordEncoder.encode(newPassWord);
                member.setPassWord(bcry_password);

                memberRepository.save(member);
                passwordChangeResponse = PasswordChangeResponse.builder()
                        .code(StatusCode.OK)
                        .message(ResponseMessage.PASSWORD_CHANGE_OK)
                        .build();
                return ResponseEntity.ok(passwordChangeResponse);
            } else {
                log.info(member.getName());
                return new ResponseEntity<>(passwordChangeResponse, HttpStatus.BAD_REQUEST);
            }
        } else {
            passwordChangeResponse.setMessage("비밀번호를 다르게 입력하세요");
            return new ResponseEntity<>(passwordChangeResponse, HttpStatus.BAD_REQUEST);
        }
    }

}