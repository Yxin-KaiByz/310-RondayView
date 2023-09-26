package com.example.a310_rondayview.ui.home;

import android.net.wifi.aware.WifiAwareChannelInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.CompositePageTransformer;
import androidx.viewpager2.widget.MarginPageTransformer;
import androidx.viewpager2.widget.ViewPager2;
import androidx.fragment.app.FragmentManager;

import com.example.a310_rondayview.R;
import com.example.a310_rondayview.data.event.EventsFirestoreManager;
import com.example.a310_rondayview.data.user.FireBaseUserDataManager;
import com.example.a310_rondayview.model.Event;
import com.example.a310_rondayview.ui.adapter.PopularEventAdaptor;
import com.example.a310_rondayview.ui.adapter.SwipeAdapter;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.yalantis.library.Koloda;
import com.yalantis.library.KolodaListener;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

import kotlin.ranges.URangesKt;

public class FragmentHome extends Fragment {

    private static final String TAG = "FragmentHome";
    private SwipeAdapter adapter;
    private PopularEventAdaptor popularEventAdaptor;
    private List<Event> events = new ArrayList<>();
    private List<Event> disinterestedEvents = new ArrayList<>();
    private List<Event> topTenPopularEvents = new ArrayList<>();
    private int currentEventIndex = 0;
    private LinearLayout buttonContainer;
    private LinearLayout emptyEventsLayout;
    Koloda koloda;
    private ViewPager2 viewPager2;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.fragment_home, container, false);
        // setting up views
        buttonContainer = rootView.findViewById(R.id.buttonsContainer);
        emptyEventsLayout = rootView.findViewById(R.id.emptyEventsLayout);
        // setting up koloda (for the card swipes) - READ MORE HERE: https://github.com/Yalantis/Koloda-Android
        koloda = rootView.findViewById(R.id.koloda);
        FragmentManager fragmentManager = getActivity().getSupportFragmentManager();
        adapter = new SwipeAdapter(getContext(), events, fragmentManager);
        koloda.setAdapter(adapter);
        //Set up UI transition for the popular events list
        topTenPopularEvents.clear();
        viewPager2 = rootView.findViewById(R.id.popularEventViewPager);
        popularEventAdaptor = new PopularEventAdaptor(getContext(), topTenPopularEvents);
        viewPager2.setAdapter(popularEventAdaptor);
        viewPager2.getChildAt(0).setOverScrollMode(RecyclerView.OVER_SCROLL_NEVER);
        CompositePageTransformer transformer = new CompositePageTransformer();
        transformer.addTransformer(new MarginPageTransformer(40));
        transformer.addTransformer(new ViewPager2.PageTransformer() {
            @Override
            public void transformPage(@NonNull View page, float position) {
                float r = 1 - Math.abs(position);
                page.setScaleY(0.3f+r*0.7f);
            }
        });
        viewPager2.setPageTransformer(transformer);

        /*
          Koloda interface listener functions, don't need to use ALL
         */
        koloda.setKolodaListener(new KolodaListener() {
            @Override
            public void onNewTopCard(int i) {
            }

            @Override
            public void onCardDrag(int i, @NonNull View view, float v) {
            }

            @Override
            public void onCardSwipedLeft(int i) {
                handleSwipe(false, currentEventIndex);
            }

            @Override
            public void onCardSwipedRight(int i) {
               handleSwipe(true, currentEventIndex);
            }

            @Override
            public void onClickRight(int i) {
            }

            @Override
            public void onClickLeft(int i) {
            }

            //TODO DETAILS PAGE (A2) -> show details of event from dialog
            @Override
            public void onCardSingleTap(int i) {

            }

            @Override
            public void onCardDoubleTap(int i) {

            }

            @Override
            public void onCardLongPress(int i) {

            }

            @Override
            public void onEmptyDeck() { // events finished - need method of refreshing!
                if (currentEventIndex == events.size()-1) {
                    emptyEventsLayout.setVisibility(View.VISIBLE);
                    koloda.setVisibility(View.GONE);
                    buttonContainer.setVisibility(View.GONE);
                    currentEventIndex = 0;
                }
            }
        });

        // fetching event data
        fetchEventData();
        fetchDisinterestedEventData();

        // Add a listener to the events Firestore collection to receive real time updates
        EventsFirestoreManager.getInstance().addEventsListener((snapshots, e) -> {

            if (e != null) {
                Log.w(TAG, "Listen failed.", e);
                return;
            }

            if (snapshots != null) {
                for (DocumentChange dc : snapshots.getDocumentChanges()) {
                    switch (dc.getType()) {
                        case ADDED:
                            // if a document was added, add it to events list
                            Event event = dc.getDocument().toObject(Event.class);
                            //Refresh the top ten event data
                            fetchTopTenEventData();
                            events.add(event);
                            // update home page?
                            break;
                        case MODIFIED:
                            // TO DO (currently no way to modify events in app)
                            break;
                        case REMOVED:
                            // TO DO (currently no way to delete events in app)
                            break;
                    }
                }
            }
        });

        // Set up button click listeners
        Button nopeButton = rootView.findViewById(R.id.nopeButton);
        Button interestedButton = rootView.findViewById(R.id.interestedButton);
        Button refreshButton = rootView.findViewById(R.id.refreshButton);

        // NOT INTERESTED
        nopeButton.setOnClickListener(v -> {
            koloda.onButtonClick(false);
            handleSwipe(false, currentEventIndex);
        });

        // INTERESTED
        interestedButton.setOnClickListener(view -> {
            koloda.onButtonClick(true);
            handleSwipe(true, currentEventIndex);
        });

        // REFRESH PAGE
        refreshButton.setOnClickListener(view -> {
            emptyEventsLayout.setVisibility(View.GONE);
            koloda.setVisibility(View.VISIBLE);
            buttonContainer.setVisibility(View.VISIBLE);
            fetchEventData(); // fetch data again
            fetchTopTenEventData();//Refresh top ten again
            koloda.reloadAdapterData();
        });

        return rootView;
    }

    /**
     * Fetches the interested event data from the event collection in DB
     * COULD store this function in another class (DatabaseService class for SRP)
     */
    private void fetchEventData() {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection("events").get().addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                events.clear();
                for (QueryDocumentSnapshot document : task.getResult()) {
                    Event event = document.toObject(Event.class);
                        events.add(event);
                        fetchTopTenEventData();
                }
                // Re-notify the adapter when the event data changed
                adapter.notifyDataSetChanged();
            } else {
                Log.e("Database error", "Fetching of events not working properly");
            }
        });
    }

    /**
     * Fetches the disinterested event data from the event collection in DB
     * COULD store this function in another class (DatabaseService class for SRP)
     */
    private void fetchDisinterestedEventData() {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection("users").get().addOnCompleteListener(task -> {    // is this the correct collection path?
            if (task.isSuccessful()) {
                disinterestedEvents.clear();
                for (QueryDocumentSnapshot document : task.getResult()) {
                    Event event = document.toObject(Event.class);
                    disinterestedEvents.add(event);
                }
                // Re-notify the adapter when the event data changed
                adapter.notifyDataSetChanged();
            } else {
                Log.e("Database error", "Fetching of disinterested events not working properly");
            }
        });
    }

    /**
     * Fetches the top ten event ranked by the amount of interests
     */
    private void fetchTopTenEventData(){
        PriorityQueue<Event> rankedEventList = new PriorityQueue<>(new Event());
//        Set<Event> elementSet = new HashSet<>();
        for(Event event : events){
                rankedEventList.add(event);
            if(rankedEventList.size()>10){
                rankedEventList.poll();
            }
        }
        topTenPopularEvents.clear();
        for(Event event : rankedEventList){
            topTenPopularEvents.add(0,event);
            popularEventAdaptor.notifyDataSetChanged();
        }
    }

    /**
     * Checks whether an event is in the disinterested events list
     */
    private boolean eventIsDisinterested(Event event) {
        if (disinterestedEvents.contains(event)) {
            return true;
        } else {
            return false;
        }
    }


    /**
     *
     * @param isInterested - if the user is interested or not
     * @param index - the particular event index
     */
    private void handleSwipe(boolean isInterested, int index) {
        currentEventIndex = index;
        if (isInterested) {
            events.get(currentEventIndex).incrementInterestedNumber();
            EventsFirestoreManager.getInstance().updateEvent(events.get(currentEventIndex));
            FireBaseUserDataManager.getInstance().addInterestedEvent(events.get(currentEventIndex));
            FireBaseUserDataManager.getInstance().getEvents(true);
        } else {
            FireBaseUserDataManager.getInstance().addDisinterestedEvent(events.get(currentEventIndex));
            FireBaseUserDataManager.getInstance().getEvents(true);
            FireBaseUserDataManager.getInstance().getEvents(false);
            // disinterested events are removed from the browse stack
            if (eventIsDisinterested(events.get(currentEventIndex))){
                events.remove(events.get(currentEventIndex));
            }
        }
        nextEvent();
        Toast.makeText(getContext(), isInterested ? "Interested" : "Not Interested", Toast.LENGTH_SHORT).show();
    }

    /**
     * Loads next event
     */
    private void nextEvent() {
        if (currentEventIndex < events.size() - 1) {
            currentEventIndex++;
            adapter.notifyDataSetChanged();
        } else { // if all events run out
           koloda.getKolodaListener().onEmptyDeck();
        }
    }

    public List<Event> getEvents() {
        return events;
    }

    public int getCurrentEventIndex() {
        return currentEventIndex;
    }
}


