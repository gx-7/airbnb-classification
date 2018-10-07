package com.airdata;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import com.airdata.database.HibernateQuery;
import com.airdata.database.HibernateSession;
import com.airdata.model.Amenity;
import com.airdata.model.Home;
import com.airdata.model.Occupancy;
import com.airdata.model.Position;
import com.airdata.model.Price;


public class Scrapper {

	public static void main(String[] args) {
		WebDriver driver = null;
		try {
			System.out.println("START Air-Data "+ Dates.todayHoursMinutes());
			
			HashMap<String, Object> images = new HashMap<String, Object>();
		    images.put("images", 2);
		    HashMap<String, Object> prefs = new HashMap<String, Object>();
		    prefs.put("profile.default_content_setting_values", images);
			
			ChromeOptions chromeOptions = new ChromeOptions();
			chromeOptions.setBinary(Xml.getChromeBinary());
			chromeOptions.setHeadless(true);
			chromeOptions.addArguments("--no-sandbox");
			chromeOptions.addArguments("--disable-gpu");
			chromeOptions.setExperimentalOption("prefs", prefs);

			driver = new ChromeDriver(chromeOptions);
			
			driver.manage().window().setSize(new Dimension(1920, 1080));
			driver.manage().timeouts().implicitlyWait(5, TimeUnit.SECONDS);
			WebDriverWait wait = new WebDriverWait(driver, 5);
			
			
				System.out.println(args[0] + " " + Dates.todayHoursMinutes());
	
				Set<String> ids = new HashSet<String>();
				requestPhase1(args[0], driver, wait, ids);	
				requestPhase2(args[0],driver, wait, ids);
				requestPhase3(args[0],driver,wait,ids);
				requestPhase4(args[0], driver, wait, ids);
				requestPhase5(args[0], driver, wait, ids);

				ids = new HashSet<String>();
				getPositions(args[0], driver, wait);
			
		}catch(Exception ex) {
			System.out.println("ERROR " + ex.getMessage());
		}finally {
			driver.close();
			driver.quit();
			HibernateSession.shutdown();
			System.out.println("END Air-Data "+ Dates.todayHoursMinutes());
		}
	}
	
	private static void getPositions(String city, WebDriver driver, WebDriverWait wait) {
		String today = Dates.today();
		System.out.println("Positions for City : "+city);
		if(!HibernateQuery.positionsDone(city,today)) {
			List<Position> positions = new ArrayList<Position>();
			Set<String> ids = new HashSet<String>();
			getIdsByPage(city, driver, wait, ids, 20, 300);
			for (int i = 0; i < ids.size(); i++) {
				Position position = new Position();
				position.setHomeId(ids.toArray()[i].toString());
				position.setPosition(String.valueOf(i+1));
				position.setDate(Dates.today());
				position.setCity(city);
				positions.add(position);
			}
			HibernateQuery.insertPositions(positions);
		}else {
			System.out.println("ERROR : Position already done "+ city + " " + today);
		}
	}

	private static void insertOrUpdateHome(String homeId, WebDriver driver, WebDriverWait wait, String city) {
		if(idAlreadyExist(homeId)) {
			if(idAlreadyUpdated(homeId)) {
				System.out.println("Update " + homeId);
				updateHome(homeId, driver, wait, city);
			}else {
				System.out.println("ERROR : Home already updated "+ homeId);
			}
		}else {
			System.out.println("Insert " + homeId);
			insertHome(homeId, driver, wait, city);
		}
	}

	private static void insertHome(String homeId, WebDriver driver, WebDriverWait wait, String city) {
		try {
			driver.get("https://fr.airbnb.com/rooms/"+homeId+"?currency=EUR");
			Home home = new Home();
			home.setHomeId(homeId);
			waituntil(driver, wait, By.xpath("//div[@class='_m7iebup']"));
			home.setCity(driver.findElement(By.xpath("//div[@class='_m7iebup']")).getText());
			String parsedCity = home.getCity();
			if(parsedCity.contains("î")) {
				parsedCity = parsedCity.replace("î", "i");
			}
			if(parsedCity.equals(city)) {
				getHomeInfos(home, driver, wait);
				getLngLat(home, driver, wait);
				insertPrice(homeId, driver, wait);
				insertOccupancy(homeId, driver, wait);
				getHomeAmenities(home, driver, wait);
				HibernateQuery.insertHome(home);
			}else {
				System.out.println("rejected city : "+ home.getCity());
			}
		}catch(NoSuchElementException nse) {
			System.out.println("ERROR 503? : city not found "+ homeId);
		}
	}
	
	private static void updateHome(String homeId, WebDriver driver, WebDriverWait wait, String city) {
		driver.get("https://fr.airbnb.com/rooms/"+homeId+"?currency=EUR");
		Home home = HibernateQuery.getHome(homeId);
		updateHomeInfos(home, driver, wait);
		insertPrice(homeId, driver, wait);
		updateOccupancy(homeId, driver, wait);
		
	}

	private static void getHomeInfos(Home home, WebDriver driver, WebDriverWait wait) {
		try {
			
		String content = driver.getPageSource();
		String basePrice = getStrBetween("\"price\":", ",\"response", content);
		if(basePrice.length()<10) {
			home.setBasePrice(getStrBetween("\"price\":", ",\"response", content));
		}else {
			System.out.println("ERROR basePrice too long : ignored");
			screenshot(driver);
		}
		home.setSurface(getSurface(content));
		home.setRating(getStrBetween("\"star_rating\":", ",\"tier_id\"", content));
		String bathStr = getStrBetween("bathroom_label\":\"", " ", content);
		if(bathStr.contains("salle")) {
			home.setBathrooms(bathStr.split("salle")[0]);
		 }else {
			 home.setBathrooms(bathStr);
		 }
		home.setBeds(getStrBetween("bed_label\":\"", " ", content));
		home.setBedrooms(getStrBetween("bedroom_label\":\"", " ", content));
		 if(home.getBedrooms().contains("\"")) {
			 home.setBedrooms(home.getBedrooms().split("\"")[0]);
		 }
		home.setGuests(getStrBetween("guest_label\":\"", " ", content));
		home.setUserId(getStrBetween("user\":\\{\"id\":", ",\"", content));
		home.setHostName(getStrBetween("host_name\":\"", "\",\"", content));
		 if(home.getHostName().contains(";")) {
			home.setHostName(home.getHostName().replaceAll(";", ""));
		 }
		 waituntil(driver, wait, By.xpath("//div[@id='host-profile']"));
		 String hostProfil = driver.findElement(By.xpath("//div[@id='host-profile']")).getText().replaceAll("\n", "");
		 try {
			 String verifiedHostStr = getStrBetween("commentaires", "Taux de r", hostProfil).substring(2);
			 if(verifiedHostStr.length()<10) {
				 home.setVerifiedHost(verifiedHostStr);
			 }else {
				 home.setVerifiedHost("non vérifié");
			 }
		 }catch(Exception e) {
			 System.out.println("ERROR : "+e.getMessage());
		 }
		 home.setResponseHost(getStrBetween("ponse :", "D", hostProfil).substring(1));
		 home.setResponseTimeHost(getStrBetween("lai de réponse", "Contacter", hostProfil).substring(3));
		 home.setLat(getStrBetween("lat\":", ",\"", content));
		 home.setLng(getStrBetween("lng\":", ",\"", content));
		 home.setAllowschildren(getStrBetween("allows_children\":", ",\"", content));
		 home.setAllowsinfants(getStrBetween("allows_infants\":", ",\"", content));
		 home.setAllowspets(getStrBetween("allows_pets\":", ",\"", content));
		 home.setAllowssmoking(getStrBetween("allows_smoking\":", ",\"", content));
		 home.setAllowsevents(getStrBetween("allows_events\":", ",\"", content));
		 home.setCalendarLastUpdated(getStrBetween("calendar_last_updated_at\":\"", "\",\"", content));
		 home.setMinNights(getStrBetween("min_nights\":", ",\"", content));
		 home.setIsBusiness(getStrBetween("is_business_travel_ready\":", ",\"", content));
		 home.setBedType(getStrBetween("bed_type\":\"", "\",\"", content));
		 home.setCancelPolicy(getStrBetween("cancel_policy\":", ",\"", content));
		 home.setCheckinRating(getStrBetween("checkin_rating\":", ",\"", content));
		 home.setCleanRating(getStrBetween("cleanliness_rating\":", ",\"", content));
		 home.setLocationRating(getStrBetween("location_rating\":", ",\"", content));
		 home.setComRating(getStrBetween("communication_rating\":", ",\"", content));
		 home.setAccuracyRating(getStrBetween("accuracy_rating\":", ",\"", content));
		 home.setPriceForQualityRating(getStrBetween("value_rating\":", ",\"", content));
		 home.setRating(getStrBetween("communication_rating\":", ",\"", content));
		 home.setInstantBook(getStrBetween("instant_book_possible\":", ",\"", content));
		 home.setTitle(getStrBetween("p3_summary_title\":\"", "\",\"", content));
		 if(home.getTitle().contains(";")) {
			 home.setTitle(home.getTitle().replaceAll(";", ""));
			 }
		 home.setReviews(getStrBetween("review_count\":", ",\"", content));
		 home.setReviewsScore(getStrBetween("review_score\":", ",\"", content));
		 home.setResponseTime(getStrBetween("response_time_shown\":", ",\"", content));
		 home.setPicturesCount(getStrBetween("picture_count\":", ",\"", content));
		 home.setSuperHost(getStrBetween("is_superhost\":", ",\"", content));
		 home.setRoomType(getStrBetween("room_type\":\"", "\",\"", content));
		 home.setLastUpdateDate(Dates.today());
		}catch(Exception s){
			System.out.println("ERROR : home ignored " + home.getHomeId());
			s.getMessage();
		}
	}

	private static String getSurface(String content) {
		String description = getStrBetween("\"sectioned_description\":", "detected_languages", content);
		String surface = "0";
		 if(description.contains("m2")){
			 surface = description.split("m2")[0].split(" ")[(description.split("m2")[0].split(" ")).length-1];
		 }
		 if(description.contains("m²")) {
				 surface = description.split("m²")[0].split(" ")[(description.split("m²")[0].split(" ")).length-1];
		 }
		 if(description.contains(" m2")){
				 surface = description.split(" m2")[0].split(" ")[(description.split(" m2")[0].split(" ")).length-1];
		 }
		 if(description.contains(" m²")) {
			 surface = description.split(" m²")[0].split(" ")[(description.split(" m²")[0].split(" ")).length-1];
		 }
		 if(surface.contains("\"")) {
			 surface  = surface.split("\"")[surface.split("\"").length-1];
		 }
		 if(surface.contains("(")) {
			 surface = surface.split("\\(")[1];
		 }
		 if(surface.contains(":")) {
			 surface = surface.split(":")[surface.split(":").length-1];
		 }
		 if(surface.contains("\\n")) {
			 surface = surface.split("\\\\n")[surface.split("\\\\n").length-1];
		 }
		return surface;
	}

	private static void updateHomeInfos(Home home, WebDriver driver, WebDriverWait wait) {
		try {
		waituntil(driver, wait, By.xpath("//div[@class='_m7iebup']"));
		String content = driver.getPageSource();
		String basePrice = getStrBetween("\"price\":", ",\"response", content);
		if(basePrice.length()<10) {
			home.setBasePrice(basePrice);
		}else {
			System.out.println("ERROR basePrice too long : ignored");
			screenshot(driver);
		}
		home.setCalendarLastUpdated(getStrBetween("calendar_last_updated_at\":\"", "\",\"", content));
		home.setMinNights(getStrBetween("min_nights\":", ",\"", content));
		home.setCancelPolicy(getStrBetween("cancel_policy\":", ",\"", content));
		home.setCheckinRating(getStrBetween("checkin_rating\":", ",\"", content));
		home.setCleanRating(getStrBetween("cleanliness_rating\":", ",\"", content));
		home.setLocationRating(getStrBetween("location_rating\":", ",\"", content));
		home.setComRating(getStrBetween("communication_rating\":", ",\"", content));
		home.setAccuracyRating(getStrBetween("accuracy_rating\":", ",\"", content));
		home.setPriceForQualityRating(getStrBetween("value_rating\":", ",\"", content));
		home.setRating(getStrBetween("communication_rating\":", ",\"", content));
		home.setInstantBook(getStrBetween("instant_book_possible\":", ",\"", content));
		home.setReviews(getStrBetween("review_count\":", ",\"", content));
		home.setReviewsScore(getStrBetween("review_score\":", ",\"", content));
		home.setResponseTime(getStrBetween("response_time_shown\":", ",\"", content));
		home.setSuperHost(getStrBetween("is_superhost\":", ",\"", content));
		home.setLastUpdateDate(Dates.today());
		HibernateQuery.updateHome(home);
		}catch(Exception ex) {
			System.out.println("ERROR 503? : updateHomeInfos ignored "+ home.getHomeId());
			ex.getMessage();
		}
	}
	
	private static void getLngLat(Home home, WebDriver driver, WebDriverWait wait) {
			if(home.getLat()==null) {	
				boolean isSuccess = waituntil(driver, wait, By.xpath("//div[@class='_1fmyluo4']"));
				if(isSuccess) {
					String href = driver.findElement(By.xpath("//div[@class='_1fmyluo4']")).getAttribute("innerHTML");
					String lnglat = getStrBetween("center=", "&amp", href);
					if(lnglat!=null && lnglat.contains(",")) {
						String[] lnglar = lnglat.split(",");
						home.setLng(lnglar[0]);
						home.setLat(lnglar[1]);
					}else {
					System.out.println("ERROR : lng lat , parsing : " + lnglat);
					}
				}else {
					System.out.println("ERROR : LngLat ignored "+ home.getHomeId());
				}
			}

	}
	
	private static void getHomeAmenities(Home home, WebDriver driver, WebDriverWait wait) {
		try {
		List<Amenity> amenities = new ArrayList<Amenity>();
		waituntil(driver, wait, By.xpath("//div[3]/div/button"));
		driver.findElement(By.xpath("//div[3]/div/button")).click();
		List<WebElement> list = driver.findElements(By.xpath("//div[@class='_rotqmn2']"));
		for (WebElement webElement : list) {
			if(!webElement.getText().equals("")) {
				Amenity amenity = new Amenity(home.getHomeId(), webElement.getText());
				amenities.add(amenity);
			}
		}
		HibernateQuery.insertAmenities(amenities);
		}catch(Exception ex) {
			System.out.println("ERROR : amenities ignored "+ home.getHomeId());
			ex.getMessage();
		}
	}

	private static void insertPrice(String homeId, WebDriver driver, WebDriverWait wait) {
		try {
		if(isNewPrice(String.valueOf(homeId))) {
			Price price = new Price();
			price.setHomeId(String.valueOf(homeId));
			waituntil(driver, wait, By.xpath("//span[@class='_10cqp947']/span"));
			price.setDate(Dates.today());
			String priceStr = driver.findElement(By.xpath("//span[@class='_10cqp947']/span")).getText();
			if(priceStr.contains("€")) {
				priceStr = priceStr.replaceAll("€", "");
			}
			price.setPrice(priceStr);
			HibernateQuery.insertPrice(price);
		}else {
			System.out.println("ERROR : Price already saved "+ homeId +" "+ Dates.today());
		}
		}catch(Exception ex) {
			System.out.println("ERROR : insertPrice ignored " + homeId);
			ex.getMessage();
		}
	}
	
	private static boolean isNewPrice(String priceId) {
		if(HibernateQuery.getPriceByIdAndDate(priceId, Dates.today())!=null) {
			return false;
		}else {
			return true;
		}
	}

	private static void insertOccupancy(String homeId, WebDriver driver, WebDriverWait wait) {
		try {
		List<Occupancy> occupancies = new ArrayList<Occupancy>();
		List<String> listM0 = getFirstMonthOccupancy(driver, wait);
		for (String literalDate : listM0) {
			Occupancy occupancy = new Occupancy();
			if(literalDate.contains(" non")) {
				literalDate = literalDate.replace(" non", "");
				occupancy.setBooked("1");
			}else {
				occupancy.setBooked("0");
			}
			String dateStr = Dates.parseSpeDateString(literalDate);
			if(Dates.dateAfterdate(dateStr, Dates.datePlusDays(Dates.today(),1))) {
				occupancy.setHomeId(homeId);
				occupancy.setDate(dateStr);
				occupancies.add(occupancy);
			}
		}
		
		List<String> listM1 = getSecondMonthOccupancy(driver);
		for (String literalDate : listM1) {
			Occupancy occupancy = new Occupancy();
			if(literalDate.contains(" non")) {
				literalDate = literalDate.replace(" non", "");
				occupancy.setBooked("1");
			}else {
				occupancy.setBooked("0");
			}
			String dateStr = Dates.parseSpeDateString(literalDate);
			occupancy.setHomeId(homeId);
			occupancy.setDate(dateStr);
			occupancies.add(occupancy);
			if(occupancies.size()==31) {
				break;
			}
		}
		HibernateQuery.insertOccupancies(occupancies);
		}catch(Exception ex) {
			System.out.println("ERROR : insertOccupancy ignored " + homeId );
			ex.getMessage();
		}
	}
	
	private static void updateOccupancy(String homeId, WebDriver driver, WebDriverWait wait) {
		try {
		List<String> listM0 = getFirstMonthOccupancy(driver, wait);
		if(listM0.size()==0) {
			System.out.println("ERROR : Occupancy passed for " + homeId);
		}else {
			int count = 0;
			for (String literalDate : listM0) {
				String booked = "";
				if(literalDate.contains(" non")) {
					literalDate = literalDate.replace(" non", "");
					booked = "1";
				}else {
					booked = "0";
				}
				String dateStr = Dates.parseSpeDateString(literalDate);
				if(Dates.dateAfterdate(dateStr, Dates.datePlusDays(Dates.today(),1))) {
					Occupancy occupancy = HibernateQuery.getOccupancy(homeId, dateStr);
					if(occupancy!=null) {
						occupancy.setBooked(booked);
						HibernateQuery.updateOccupancy(occupancy);
					}else {
						occupancy = new Occupancy();
						occupancy.setDate(dateStr);
						occupancy.setBooked(booked);
						occupancy.setHomeId(homeId);
						HibernateQuery.insertOccupancy(occupancy);
					}
					count++;
				}
			}
				List<String> listM1 = getSecondMonthOccupancy(driver);
			for (String literalDate : listM1) {
				String booked = "";
				if(literalDate.contains(" non")) {
					literalDate = literalDate.replace(" non", "");
					booked = "1";
				}else {
					booked = "0";
				}
				String dateStr = Dates.parseSpeDateString(literalDate);
				Occupancy occupancy = HibernateQuery.getOccupancy(homeId, dateStr);
				if(occupancy!=null) {
					occupancy.setBooked(booked);
					HibernateQuery.updateOccupancy(occupancy);
				}else {
					occupancy = new Occupancy();
					occupancy.setDate(dateStr);
					occupancy.setBooked(booked);
					occupancy.setHomeId(homeId);
					HibernateQuery.insertOccupancy(occupancy);
				}
				if(count==30) {
					break;
				}
				count++;
			}
		}
		}catch(Exception ex) {
			System.out.println("ERROR : updateOccupancy ignored " + homeId );
			ex.getMessage();
		}
	}

	private static List<String> getSecondMonthOccupancy(WebDriver driver) {
		String calendarM1Str = driver.findElement(By.xpath("(//table[@class='_p5jgym'])[3]")).getAttribute("innerHTML");
		List<String> listM1 = getStrBetweenMultiple("aria-label=\"", "\"", calendarM1Str);
		return listM1;
	}

	private static List<String> getFirstMonthOccupancy(WebDriver driver, WebDriverWait wait) {
		waituntil(driver, wait, By.xpath("//input[@id='checkin']"));
		driver.findElement(By.xpath("//input[@id='checkin']")).click();
		boolean calendarSuccess = waituntil(driver, wait, By.xpath("(//table[@class='_p5jgym'])[2]"));
		try {
			Thread.sleep(100);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		List<String> listM0 = new ArrayList<String>();
		if(calendarSuccess == true) {
			String calendarM0Str = driver.findElement(By.xpath("(//table[@class='_p5jgym'])[2]")).getAttribute("innerHTML");
			listM0 = getStrBetweenMultiple("aria-label=\"", "\"", calendarM0Str);
			int count = 0;
			for (String el : listM0) {
				if(el.contains("non")) {
					count++;
				}
			}
			if(count>=30) {
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				calendarM0Str = driver.findElement(By.xpath("(//table[@class='_p5jgym'])[2]")).getAttribute("innerHTML");
				listM0 = getStrBetweenMultiple("aria-label=\"", "\"", calendarM0Str);
				count = 0;
				for (String el : listM0) {
					if(el.contains("non")) {
						count++;
					}
				}
				if(count>=30) {
					try {
						Thread.sleep(200);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
					calendarM0Str = driver.findElement(By.xpath("(//table[@class='_p5jgym'])[2]")).getAttribute("innerHTML");
					listM0 = getStrBetweenMultiple("aria-label=\"", "\"", calendarM0Str);
				}
			}
		}
		
		return listM0;
	}
	
	
	private static String getStrBetween(String before, String after, String content) {
		final Pattern pattern = Pattern.compile(before + "(.+?)" + after);
		final Matcher matcher = pattern.matcher(content);
		if(matcher.find()) {
			return matcher.group(1);
		}else {
			System.out.println("ERROR : between "+ before +" and " + after + " not found ");
			return "Error";
		}
	}
	
	private static List<String> getStrBetweenMultiple(String before, String after, String content) {
		List<String> list = new ArrayList<String>();
		final Pattern pattern = Pattern.compile(before + "(.+?)" + after);
		final Matcher matcher = pattern.matcher(content);
		while(matcher.find()) {
			if(matcher.group(1).contains(" comme")) {
				list.add(getStrBetween(", ", " comme",matcher.group(1)));
			}
			if(matcher.group(1).contains("non disponible")) {
				list.add(getStrBetween(", ", " disponible",matcher.group(1)));
			}
		}
		if(list !=null && list.size() !=0) {
			return list;
		}else {
			System.out.println("ERROR : betweenMulti "+before +" and " + after + " not found ");
			return null;
		}
	}

	private static boolean idAlreadyExist(String homeId) {	
		if(HibernateQuery.getHome(homeId)!=null) {
			return true;
		}else {
			return false;
		}
	}
	
	private static boolean idAlreadyUpdated(String homeId) {
		if(HibernateQuery.getHome(homeId).getLastUpdateDate() == Dates.today()) {
			return false;
		}else {
			return true;
		}
	}

	/**
	 * 1
	 * 1-24 
	 */
	private static void  requestPhase1(String city, WebDriver driver, WebDriverWait wait, Set<String> ids) {
		System.out.println("Phase 1) 1 - 24 "+Dates.todayHoursMinutes());
		ids = new HashSet<String>();
		getIdsByPage(city, driver, wait, ids, 1, 24);
		for (String id : ids) {
			insertOrUpdateHome(id, driver, wait, city);
		}
	}


	/**
	 * 22
	 * 
	 * 25,26-29,30,31-34,35,36-39,40,41-44,45,46-49,50,51-54,55,56-59,60,61-64,65,66-69,70,71-74,75,76-79
	 */
	private static void requestPhase2(String city, WebDriver driver, WebDriverWait wait, Set<String> ids) {
		System.out.println("Phase 2) 25 - 79 "+Dates.todayHoursMinutes());
		Integer minPrice = 25;
		Integer maxPrice = 25;
		for (int i = 0; i < 22; i++) {
			ids = new HashSet<String>();
			if(i%2 == 0) {
				getIdsByPage(city, driver, wait, ids, minPrice, maxPrice);
				minPrice++;
				maxPrice = maxPrice + 4;
			}else {
				getIdsByPage(city, driver, wait, ids, minPrice, maxPrice);
				maxPrice++;
				minPrice = maxPrice;
			}
			for (String id : ids) {
				insertOrUpdateHome(id, driver, wait, city);
			}
		}
	}
	
	/**
	 * 5
	 * 80,81-89,90,91-99,100
	 */
	private static void requestPhase3(String city, WebDriver driver, WebDriverWait wait, Set<String> ids) {
		System.out.println("Phase 3) 80 - 100 "+Dates.todayHoursMinutes());
		Integer minPrice = 80;
		Integer maxPrice = 80;
		for (int i = 0; i < 5; i++) {
			ids = new HashSet<String>();
			if(i%2 == 0) {
				getIdsByPage(city, driver, wait, ids, minPrice, maxPrice);
				minPrice++;
				maxPrice = maxPrice + 9;
			}else {
				getIdsByPage(city, driver, wait, ids, minPrice, maxPrice);
				maxPrice++;
				minPrice = maxPrice;
			}
			for (String id : ids) {
				insertOrUpdateHome(id, driver, wait, city);
			}
		}
	}
	
	/**
	 * 8
	 * 101-125,126-150,151-175,176-200,201-225,226-250,251-275,276-300
	 */
	private static void requestPhase4(String city, WebDriver driver, WebDriverWait wait, Set<String> ids) {
		System.out.println("Phase 4) 101 - 300 "+Dates.todayHoursMinutes());
		Integer minPrice = 101;
		Integer maxPrice = 125;
		for (int i = 0; i < 8; i++) {
				ids = new HashSet<String>();
				getIdsByPage(city, driver, wait, ids, minPrice, maxPrice);
				minPrice = maxPrice+1;
				maxPrice = maxPrice+25;
				for (String id : ids) {
					insertOrUpdateHome(id, driver, wait, city);
				}
		}
	}
	
	/**
	 * 1
	 * 301-1000
	 */
	private static void requestPhase5(String city, WebDriver driver, WebDriverWait wait, Set<String> ids) {
		System.out.println("Phase 4) 301 - 1000 "+Dates.todayHoursMinutes());
		ids = new HashSet<String>();
		getIdsByPage(city, driver, wait, ids, 301, 1000);
		for (String id : ids) {
			insertOrUpdateHome(id, driver, wait, city);
		}
	}
	
// check doublons, check first insert in the id list
	private static void getIdsByPage(String city, WebDriver driver, WebDriverWait wait, Set<String> ids,
			Integer minPrice, Integer maxPrice) {
		try {
			driver.get("https://fr.airbnb.com/s/"+city+"/homes?currency=EUR&refinement_paths[]=%2Fhomes&allow_override[]=&room_types[]=Entire home%2Fapt");
		
			waituntil(driver, wait, By.xpath("//button[@title='Zoom avant']"));
			driver.findElement(By.xpath("//button[@title='Zoom avant']")).click();
		
			waituntil(driver, wait, By.xpath("//button[@title='Zoom arrière']"));
			driver.findElement(By.xpath("//button[@title='Zoom arrière']")).click();
		

			String baseUrl = driver.getCurrentUrl();
			if(baseUrl.contains("s_tag")) {
				baseUrl = baseUrl.split("s_tag")[0];
			}
			if(baseUrl.contains("zoom")) {
				baseUrl = baseUrl.replace("zoom","");
			}
			driver.get(baseUrl+"&price_min="+minPrice+"&price_max="+maxPrice);
			Integer pageNumber = getPageNumber(driver, wait);
			if(pageNumber !=null) {
				for (int k = 0; k < pageNumber; k++) {
						if(k!=0)
						driver.get(baseUrl+"&price_min="+minPrice+"&price_max="+maxPrice+"&section_offset="+k);
					waituntil(driver, wait, By.xpath("//a[@class='_15ns6vh']"));
					List<WebElement> webIds = driver.findElements(By.xpath("//a[@class='_15ns6vh']"));
					for (WebElement webElement : webIds) {
						ids.add(webElement.getAttribute("target").replace("listing_",""));
					}
					System.out.println(city +", ids: " + ids.size() + ", price: "+ minPrice +"-"+maxPrice + ", page: "+ (k+1));
				}
			}
		}catch(Exception ex) {
			System.out.println("ERROR " + ex.getMessage());
		}
	}
	

	private static Integer getPageNumber(WebDriver driver, WebDriverWait wait) {
		try {
			Integer homesNumber = getHomeNumber(driver,wait);
			if(homesNumber%18 == 0) {
				return homesNumber/18;
			}else {
				return Integer.valueOf((int)Math.floor((double)getHomeNumber(driver,wait)/(double)18)) + 1;
			}
		}catch(Exception ex) {
			return null;
		}
	}

	private static Integer getHomeNumber(WebDriver driver, WebDriverWait wait) {
		try {
			waituntil(driver, wait, By.xpath("//div[@class='_12to336']"));
			String homeNumber = driver.findElement(By.xpath("//div[@class='_12to336']")).getText().split("sur ")[1].split(" location")[0];
			if(homeNumber.contains("+")) {
				homeNumber = homeNumber.split("\\+")[0];
			}
			return Integer.valueOf(homeNumber);
		}catch(Exception ex) {
			try {
				waituntil(driver, wait, By.xpath("//div[@class='_13nd2f7d']"));
				System.out.println("No Home for this range");
			}catch(Exception e) {
				System.out.println("ERROR 503? "+ driver.getCurrentUrl());
				screenshot(driver);
			}
			return null;
		}
	}


	private static void screenshot(WebDriver driver) {
		File srcFile = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
		try {
			System.out.println(Xml.getErrorPath() + "screenshotLastError.png");
			File file = new File(Xml.getErrorPath() + "screenshotLastError.png");
			file.delete();
			FileUtils.copyFile(srcFile, file);
		} catch (IOException e) {
			e.getMessage();
		}
	}

	private static boolean waituntil(WebDriver driver, WebDriverWait wait, By xpath)  {
		try {
			wait.until(ExpectedConditions.presenceOfElementLocated(xpath));
			return true;
		}catch(org.openqa.selenium.NoSuchElementException | org.openqa.selenium.TimeoutException e ) {
			System.out.println("ERROR : TRY HARD : 5sec : " + xpath.toString() + " : "+ driver.getCurrentUrl());
			try {
			driver.navigate().to(driver.getCurrentUrl());
			wait.until(ExpectedConditions.presenceOfElementLocated(xpath));
			return true;
			}catch(org.openqa.selenium.NoSuchElementException | org.openqa.selenium.TimeoutException ex ) {
				System.out.println("ERROR : TRY HARD : 10sec : " + xpath.toString() + " : " + driver.getCurrentUrl());
					try {
					driver.manage().timeouts().implicitlyWait(10, TimeUnit.SECONDS);
					driver.navigate().to(driver.getCurrentUrl());
					screenshot(driver);
					wait.until(ExpectedConditions.presenceOfElementLocated(xpath));
					driver.manage().timeouts().implicitlyWait(5, TimeUnit.SECONDS);
					return true;
					}catch(org.openqa.selenium.NoSuchElementException | org.openqa.selenium.TimeoutException exce ) {
						System.out.println(e.getMessage());
						System.out.println(xpath.toString() + " : " + driver.getCurrentUrl());
						return false;
					}
			}
		}
	}
}

