package java.game;

import java.io.*;
import java.util.*;
import java.util.resource.*;
import java.render.*;	//Text
import java.render.osd.*;	//Text
import java.sound.*;

public class Bot extends Racer
{
    int		fileid;
    int		seed;
    float   aiLevel;
    int		debugid;

	//int		carID;
	VehicleDescriptor botVd;
	VehicleDescriptor nightVd;

	GameRef	dummycar;	//dublor, native-only instance

	//random generalashoz:
	float	color, optical, engine;

	int		imaPoliceDriver;
	int             nightWins = 0;
	int             nightLoses = 0;
        float           bestNightQM = 0.0;

	static	String[]	botNames;

	GameRef world;

    GameRef brain;          //ha ai-kent mukodik
    GameRef controller;		// ==brain :o), patch
    int		traffic_id;     //ha a forgalomban van
	int		horn;

    public Bot( int savefileid, int rndseed, float col, float opti, float eng, float ai)
	{
        fileid=savefileid;
        seed=rndseed;
        name=constructName( seed );
		character = new RenderRef( RID_FEJ + fileid );

		//------
		color = col;
		optical = opti;
		engine = eng;
		aiLevel = ai;
		//------

		if( name.length() % 2 )
			driverID = GameLogic.HUMAN_OPPONENT;
		else
			driverID = GameLogic.HUMAN_OPPONENT2;

        setEventMask( EVENT_COMMAND );
	}

	public Bot( int savefileid, int rndseed, float level )	//level: [0..1)
    {
		if( !botNames )
			patchCreateBotNames();

        fileid=savefileid;
        seed=rndseed;
        name=botNames[fileid];
		character = new RenderRef( RID_FEJ + fileid );


		//------
		int tmp;	//tortresz :)
		color = rndseed*1.36785; tmp=color; color-=tmp;
		optical= rndseed*3.13771; tmp=optical; optical-=tmp;
		engine= rndseed*4.75835; tmp=engine; engine-=tmp;

		engine = 1.0 + engine*level;	//
		optical += 1.0;

		aiLevel=level;
		//------

		if( name.length() % 2 )
			driverID = GameLogic.HUMAN_OPPONENT;
		else
			driverID = GameLogic.HUMAN_OPPONENT2;

        setEventMask( EVENT_COMMAND );
    }

	public RenderRef getMarker()
	{
		if( club == 2 )
			return Marker.RR_CAR3;
		if( club == 1 )
			return Marker.RR_CAR2;

		return Marker.RR_CAR1;
	}

	public float getCarPrestige()
	{
		if( !car && botVd )
			return botVd.estimatePrestige();

		return super.getCarPrestige();
	}				

	public void setDriverObject( int id )
	{
		driverID=id;
	}

    //letrehozas, megszuntetes:

	//fajlbol:
    public void createCar( GameRef map, String filename )
	{
		Vehicle vhc = Vehicle.load( filename, this );

		if( !vhc )
			System.exit( "Fatal: Cannot create car using file " + filename );

		createCar( map, vhc );
	}

	//automatikusan:
    public void createCar( GameRef map )
	{
		Vehicle vhc;

		if( botVd )
		{
			vhc = new Vehicle( this, botVd.id,  botVd.colorIndex, botVd.optical, botVd.power, botVd.wear, botVd.tear );
		}
		else
		{
			System.exit( "Bot.createCar(): VehicleDescriptor null" );
		}

		createCar( map, vhc );
	}

	//atadjuk neki direktbe:
    public void createCar( GameRef map, Vehicle c )
    {
        world=map;
		deleteCar();

		car = c;
		debugid = car.id();

		enterCar( car );
    }


    public void deleteCar()
    {
		if( car )
		{
			leaveCar(0);

			//kocsi bezuzva
			if( car.id() )
			// System.log( "deleted: " + car.id() );
			car.destroy();
			car=null;       //eleg lenne csak ez, majd teszteljuk le!
		}
    }

	public void enterCar( Vehicle c )
	{
		if (!c)	return;

		c.setTransmission( Vehicle.TRANSMISSION_SEMIAUTO );
        
        //megcsinaljuk az agyat
		//if( brain )	//pl volt mar ha epp forgalomba maszott vissza a dummycar, es kihivtuk
		//	brain.destroy();


		brain = new GameRef( world, sl:0x0000006Er, "", "BOTBRAIN");
		controller = brain;

		//ilyen okos vagy:
        brain.queueEvent( null, GameType.EVENT_COMMAND, "AI_level " + aiLevel );

        //ultetunk bele emberket is:
		c.command( "corpse 0 0" );	//kiszedjuk ha mar volt hullaja :)

        render = new RenderRef( world, driverID, "botfigura" );
        brain.queueEvent( null, GameType.EVENT_COMMAND, "renderinstance " + render.id() );

        //ezt a kocsit vezessed ni:
        brain.queueEvent( null, GameType.EVENT_COMMAND, "controllable " + c.id() );
        brain.queueEvent( null, GameType.EVENT_COMMAND, "AI_suspend" );


		//addNotification( c, EVENT_COMMAND, EVENT_SAME, null );
        //setEventMask( EVENT_COMMAND );
	}

	public void leaveCar( int leaveInTraffic )
	{
		//clearEventMask( EVENT_COMMAND );

		if( !leaveInTraffic )
		{
			leaveTraffic();	//kiszedi a forgalombol, ha ott volt
		}
	
		//sofor killed
		if( render )
			render.destroy();

		//agya is killed
		if (brain)
		{
			brain.queueEvent( null, GameType.EVENT_COMMAND, "leave " + car.id() );
			brain.destroy();
			brain = null;
			controller = null;
		}

		//remNotification( car, EVENT_COMMAND );

		if( leaveInTraffic )
		{
			//native gamerefbol keszult vehicles:
			//ha forgalomba engedjuk vissza, ki kell torolnunk a Vehicle-bol, kulonben torolni fogja a gc!
			if( car )	//ez miert kelll???!!!
			{
				car.release();
				car=null;
			}
		}
		
	}

    public void handleEvent( GameRef obj_ref, int event, int param )
    {
		if( event == EVENT_TIME )
		{
			clearEventMask( EVENT_TIME );

			reJoinTraffic();
		}
	}

    //itt szinkronizalunk az automatikus forgalomkezeles esemenyeihez:
    //ha karambolozik az auto, automatikusan kikerul a forgalombol, (denes kuld infot)
    //visszakuldom a forgalomba, (AI_GoToTraffic)
    //majd visszakerul a forgalomba. (klampi csinalja)
    public void handleEvent( GameRef obj_ref, int event, String param )
    {
		if( event == EVENT_COMMAND )
		{
			String t0 = param.token(0);
			if( t0 == "ai_info_entertraffic" )
			{
				traffic_id = param.token(1).intValue();

				if( imaPoliceDriver )
				{
					remNotification( car, EVENT_COMMAND );
					leaveCar(1); //lassu! de ez nincs olyan gyakran...
					//majd a traffic id miatt eszreveszi a city!
				}
				else
				{
					//leaveCar(1); lassu!

					brain.destroy();
					brain = null;
					controller = null;

					RenderRef render = new RenderRef( world, driverID, "botfigura-leavetraffic" );
					car.command( "corpse 0 " + render.id() );

					car.release();
					car = null;

				}
			}
			else
			if( t0 == "ai_info_leavetraffic" )
			{
				traffic_id = 0;

				car = new Vehicle(dummycar);
				//enterCar( car ); lassu!

				brain = new GameRef( world, sl:0x0000006Er, "", "BOTBRAIN");
				controller = brain;

				dummycar.command( "corpse 0 0" );
				RenderRef render = new RenderRef( world, driverID, "botfigura-leavetraffic" );
				brain.command( "renderinstance " + render.id() );

				brain.queueEvent( null, GameType.EVENT_COMMAND, "controllable " + car.id() );


				stop();

				setEventMask( EVENT_TIME );
				addTimer( 3.0, 0 );
			}
		}
    }


    //parancsok:
    public void setTrafficBehaviour( int mode )
    {
		if( traffic_id && world instanceof GroundRef )
		{
			((GroundRef)world).setTrafficCarBehaviour( traffic_id, mode );
		}
    }

    //az adott poziciohoz legkozelebbi keresztezodesnel csatlakozik a forgalomhoz
    public void joinTraffic( Vector3 pos )
    {
		if( !traffic_id )
        {
	        beStupid();
			traffic_id = world.addTrafficCar( car, pos );
        }
	}

    public void reJoinTraffic()
    {
		if( !traffic_id )
		{
            //leaveTraffic(); elvarjuk, hogy hivaskor mar ne legyen benne
			if (brain)
	            brain.queueEvent( null, GameType.EVENT_COMMAND, "AI_GoToTraffic" );
            //ha odaert, onmukododen traficce valik... ekkor notificationt kapok rola (az ai lesuspendelodik, ha en csinaltam)
		}
    }

    public void leaveTraffic()
    {
        if( traffic_id )
        {
			world.remTrafficCar( traffic_id );
			traffic_id = 0;
        }
    }

    public void beStupid()
    {
            brain.queueEvent( null, GameType.EVENT_COMMAND, "AI_suspend" );
    }

    public void followCar( GameRef playercar, float dest )
    {
            leaveTraffic();
            brain.queueEvent( null, GameType.EVENT_COMMAND, "AI_follow 0,0," + dest + " " + playercar.id() );
    }

    public void stopCar( GameRef playercar )
    {
            leaveTraffic();
            brain.queueEvent( null, GameType.EVENT_COMMAND, "AI_follow 0,0,-2 " + playercar.id() );
    }

    public void startRace( Vector3 destination, Racer opponent )
    {
            leaveTraffic();
            brain.queueEvent( null, GameType.EVENT_COMMAND, "AI_race " + destination.toString() + " " + opponent.car.id() );
    }

    public void driveStraightTo( Vector3 destination )
    {
            leaveTraffic();
            brain.queueEvent( null, GameType.EVENT_COMMAND, "AI_GoToTarget " + destination.toString() );
    }

    public void pressHorn()
    {
		if (horn) return;
            //  brain.queueEvent( null, GameType.EVENT_COMMAND, "AI_horn 1" );
		if (car)
		{
            car.queueEvent( null, GameType.EVENT_COMMAND, "sethorn 1" );
			horn = 1;
		} else
		if (dummycar)
		{
            dummycar.queueEvent( null, GameType.EVENT_COMMAND, "sethorn 1" );
			horn = 1;
		}
    }

    public void releaseHorn()
    {
		if (!horn) return;
            //  brain.queueEvent( null, GameType.EVENT_COMMAND, "AI_horn 0" );
		if (car)
		{
            car.queueEvent( null, GameType.EVENT_COMMAND, "sethorn 0" );
			horn = 0;
		} 
		else
		if (dummycar)
		{
            dummycar.queueEvent( null, GameType.EVENT_COMMAND, "sethorn 0" );
			horn = 0;
		}
    }

    public void stop()
    {
		leaveTraffic();
		if (brain)
			brain.queueEvent( null, GameType.EVENT_COMMAND, "AI_stop" );
		//beStupid();
    }

	public void followSplineTrack( float width, String splineFile, int oppCarId )
	{
		brain.queueEvent( null, EVENT_COMMAND, "AI_spline " + width + " " + splineFile + " " + oppCarId );					
	}

    public String constructName( int seed )
    {
            String[] pre = new String[20];
            pre[0]="Dr. ";
            pre[1]="Old ";

            String[] first = new String[12];
            first[0]="John ";
            first[1]="David ";
            first[2]="Bill ";
            first[3]="Stewart ";
            first[4]="Joe ";
            first[5]="Sam ";
            first[6]="Alan ";
            first[7]="Marc ";
            first[8]="Jason ";
            first[9]="Sean ";
            first[10]="Tony ";
            first[11]="Leo ";

            String[] mid = new String[40];
            mid[0]="'Lucky' ";
            mid[1]="'Speedy' ";
            mid[2]="'Swifty' ";
            mid[3]="'Ugly' ";
            mid[4]="'Bighead' ";
            mid[5]="'Bugsy' ";
            mid[6]="'Fuzzy' ";
            mid[7]="'Wacky' ";
            mid[8]="'Scottish' ";
            mid[9]="'Danish' ";
            mid[10]="'Looser' ";
            mid[11]="'GearHead' ";
            mid[12]="'Skinny' ";

            String[] last = new String[22];
            last[0]="Galahad";
            last[1]="Butterfly";
            last[2]="Robertson";
            last[3]="Cocker";
            last[4]="Johnson";
            last[5]="Livingstone";
            last[6]="Dunnigan";
            last[7]="Little";
            last[8]="Luciano";
            last[9]="Evans";
            last[10]="Murphy";
            last[11]="Speaker";
            last[12]="Sterkovic";
            last[13]="Scott";
            last[14]="McDonell";
            last[15]="Bonnett";
            last[16]="Bakers";
            last[17]="Perkins";
            last[18]="Olson";
            last[19]="Polansky";
            last[20]="O'Connor";
            last[21]="Kozak";

            String[] post = new String[20];
            post[0]=" jr.";

            return //pre[(seed*3)%pre.length] +
                    first[(seed*19)%first.length] +
              //      mid[(seed*31)%mid.length] +
                    last[(seed*23)%last.length] +
                    post[(seed*17)%post.length];

    }

	public String getPrestigeString()
        {
               	return getPrestigeString(-1);
        }

	public String getPrestigeString(int racemode)
	{
		int pprestige, cprestige, aprestige;
		pprestige = prestige*PRESTIGE_SCALE;

		if( car )
			cprestige = car.getPrestige() * VHC_PRESTIGE_SCALE;
                else
                if (racemode == 0 || racemode == 1)
                  cprestige = nightVd.estimatePrestige() * VHC_PRESTIGE_SCALE;
                else
            //    if (racemode == 4 || racemode == 5)
                  cprestige = botVd.estimatePrestige() * VHC_PRESTIGE_SCALE;

		aprestige = pprestige*0.5 + cprestige*0.5;

		// return aprestige + " (" + pprestige +  ":" + cprestige + ")";
		return pprestige +  "/" + cprestige;
	}

	public void patchCreateBotNames()
	{
		botNames = new String[GameLogic.CLUBS*GameLogic.CLUBMEMBERS];

		botNames[0]  = "Trevor Banks";
		botNames[1]  = "Sam Cranston";
		botNames[2]  = "Nick Blitzer";
		botNames[3]  = "Kip Dragonarm";
		botNames[4]  = "Marcus Lewis";
		botNames[5]  = "Wolfe Johnson";
		botNames[6]  = "Oliver Stranhand";
		botNames[7]  = "Otaranto Nogadachi";
		botNames[8]  = "Pete McMorrow";
		botNames[9]  = "Kenny Surestar";	//G!
		botNames[10] = "Victor Speedwheel";
		botNames[11] = "Darren O'Donnel";
		botNames[12] = "Bob Rockslate";
		botNames[13] = "Race Slade";
		botNames[14] = "Freddie Jones";
		botNames[15] = "Jennifer Angelstone";	//G!
		botNames[16] = "Cody Emerson";
		botNames[17] = "Bradley Wheelwright";
		botNames[18] = "Blake Kane";
		botNames[19] = "Buck Ironhead";
		botNames[20] = "Kane Magnum";
		botNames[21] = "Sebastian Fox";
		botNames[22] = "Old Sean Dunnigan";
		botNames[23] = "Max Sharpaxe";
		botNames[24] = "Shayne Phoenix";
		botNames[25] = "Crash Bradley";
		botNames[26] = "Dash Thunder";
		botNames[27] = "Dick Midnightwill";
		botNames[28] = "Monica Slangster";	//G!
		botNames[29] = "Lance Kane";
		botNames[30] = "Darren Lonemidnight";
		botNames[31] = "Joey Barrow";
		botNames[32] = "Toranuchi Sizukan";
		botNames[33] = "Buzz Shadowhand";
		botNames[34] = "Tex Rockstrike";
		botNames[35] = "Buck Armstrong";
		botNames[36] = "Pierce Camspear";
		botNames[37] = "Derek Buchanan";
		botNames[38] = "Winford Pierce";
		botNames[39] = "Debra Wingside";	//G!
		botNames[40] = "Jacky Slater";
		botNames[41] = "Brian Corrigan";
		botNames[42] = "Unorua Starchest";	//G!
		botNames[43] = "Jimmy Ranger";
		botNames[44] = "Yagimoto Iimu";
		botNames[45] = "Benton Fury";
		botNames[46] = "Dean Sinatra";
		botNames[47] = "Bradley Savage";
		botNames[48] = "Remington Benson";
		botNames[49] = "Rocky Braveshot";
		botNames[50] = "Ace Cranston";
		botNames[51] = "Johnnie Marshall";
		botNames[52] = "Mitchell Campbell";
		botNames[53] = "Nathaniel Owens";
		botNames[54] = "Raymond Payne";
		botNames[55] = "Mitsuoma Sabodo";	//G!
		botNames[56] = "William Dawson";
		botNames[57] = "Charlie Wilson";
		botNames[58] = "Bad Anderson";
		botNames[59] = "Clayton Hudson";
	}

	public void save( File saveGame )
	{
               super.save(saveGame);

		int save_ver = 1;
		saveGame.write(save_ver);
		if (save_ver >= 1)
		{
			saveGame.write(nightWins);
			saveGame.write(nightLoses);
			saveGame.write(bestNightQM);
		}
	}

	public void load( File saveGame )
	{
               super.load(saveGame);

		int save_ver;
		save_ver = saveGame.readInt();

		if (save_ver >= 1)
		{
			nightWins = saveGame.readInt();
			nightLoses = saveGame.readInt();
			bestNightQM = saveGame.readFloat();
		}
	}
}
