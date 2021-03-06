/*******************************************************************************
 *
 *                          Messenger Android Frontend
 *                           (C) 2017 Björn Petersen
 *                    Contact: r10s@b44t.com, http://b44t.com
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see http://www.gnu.org/licenses/ .
 *
 ******************************************************************************/


package com.b44t.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.ListView;

import com.b44t.messenger.AndroidUtilities;
import com.b44t.messenger.MediaController;
import com.b44t.messenger.ApplicationLoader;
import com.b44t.messenger.R;
import com.b44t.ui.Adapters.BaseFragmentAdapter;
import com.b44t.ui.Cells.ShadowSectionCell;
import com.b44t.ui.Cells.TextCheckCell;
import com.b44t.ui.Cells.TextSettingsCell;
import com.b44t.ui.ActionBar.ActionBar;
import com.b44t.ui.ActionBar.BaseFragment;
import com.b44t.ui.Components.LayoutHelper;
import com.b44t.ui.Components.NumberPicker;


public class SettingsAdvActivity extends BaseFragment {

    // the list
    private int directShareRow, textSizeRow, cacheRow, raiseToSpeakRow, sendByEnterRow, autoplayGifsRow, finalShadowRow;
    private int rowCount;

    private static final int ROWTYPE_SHADOW          = 0;
    private static final int ROWTYPE_TEXT_SETTINGS   = 1;
    private static final int ROWTYPE_CHECK           = 2;
    private static final int ROWTYPE_COUNT           = 3;

    private ListView listView;


    public static int defMsgFontSize() {
        return AndroidUtilities.isTablet() ? 18 : 16;
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();

        rowCount = 0;
        if (Build.VERSION.SDK_INT >= 23) {
            directShareRow = -1; // for now, seems not really to work, however, in T'gram it does
        }
        else {
            directShareRow = -1;
        }
        textSizeRow = rowCount++;
        autoplayGifsRow = rowCount++;
        sendByEnterRow = rowCount++;
        raiseToSpeakRow = rowCount++; // outgoing message
        cacheRow = -1;// for now, the - non-functional - page is reachable by the "storage settings" in the "android App Settings" only
        finalShadowRow = rowCount++;

        return true;
    }

    @Override
    public View createView(Context context)
    {
        // create action bar
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(ApplicationLoader.applicationContext.getString(R.string.AdvancedSettings));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        // create object to hold the whole view
        fragmentView = new FrameLayout(context) {};
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        frameLayout.setBackgroundColor(0xfff0f0f0);

        // create the main layout list
        ListAdapter listAdapter = new ListAdapter(context);

        listView = new ListView(context);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setDrawSelectorOnTop(true);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.START));
        listView.setAdapter(listAdapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, final int i, long l) {
                if (i == textSizeRow) {
                    if (getParentActivity() == null) {
                        return;
                    }
                    AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                    builder.setTitle(ApplicationLoader.applicationContext.getString(R.string.TextSize));
                    final NumberPicker numberPicker = new NumberPicker(getParentActivity());
                    final int MIN_VAL = 12;
                    final int MAX_VAL = 30;
                    final int DEF_VAL = defMsgFontSize();
                    String displayValues[] = new String[MAX_VAL-MIN_VAL+1];
                    for( int v = MIN_VAL; v <= MAX_VAL; v++ ) {
                        String cur = String.format("%d", v);
                        if( v==DEF_VAL ) {
                            cur += " (" +ApplicationLoader.applicationContext.getString(R.string.Default)+ ")";
                        }
                        displayValues[v-MIN_VAL] = cur;
                    }
                    numberPicker.setMinValue(MIN_VAL);
                    numberPicker.setMaxValue(MAX_VAL);
                    numberPicker.setDisplayedValues(displayValues);
                    numberPicker.setWrapSelectorWheel(false);
                    numberPicker.setValue(ApplicationLoader.fontSize);
                    builder.setView(numberPicker);
                    builder.setNegativeButton(ApplicationLoader.applicationContext.getString(R.string.Done), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
                            SharedPreferences.Editor editor = preferences.edit();
                            editor.putInt("msg_font_size", numberPicker.getValue());
                            ApplicationLoader.fontSize = numberPicker.getValue();
                            editor.apply();
                            if (listView != null) {
                                listView.invalidateViews();
                            }
                        }
                    });
                    showDialog(builder.create());
                } else if (i == sendByEnterRow) {
                    SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
                    boolean send = preferences.getBoolean("send_by_enter", false);
                    SharedPreferences.Editor editor = preferences.edit();
                    editor.putBoolean("send_by_enter", !send);
                    editor.apply();
                    if (view instanceof TextCheckCell) {
                        ((TextCheckCell) view).setChecked(!send);
                    }
                } else if (i == raiseToSpeakRow) {
                    MediaController.getInstance().toogleRaiseToSpeak();
                    if (view instanceof TextCheckCell) {
                        ((TextCheckCell) view).setChecked(MediaController.getInstance().canRaiseToSpeak());
                    }
                } else if (i == autoplayGifsRow) {
                    MediaController.getInstance().toggleAutoplayGifs();
                    if (view instanceof TextCheckCell) {
                        ((TextCheckCell) view).setChecked(MediaController.getInstance().canAutoplayGifs());
                    }
                } else if(i == directShareRow) {
                    MediaController.getInstance().toggleDirectShare();
                    if (view instanceof TextCheckCell) {
                        ((TextCheckCell) view).setChecked(MediaController.getInstance().canDirectShare());
                    }
                } else if (i == cacheRow) {
                    presentFragment(new CacheControlActivity());
                }
            }
        });

        return fragmentView;
    }

    private class ListAdapter extends BaseFragmentAdapter {
        private Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int i) {
            return i == textSizeRow ||
                    i == sendByEnterRow ||
                    i == cacheRow || i == raiseToSpeakRow || i == autoplayGifsRow || i == directShareRow;
        }

        @Override
        public int getCount() {
            return rowCount;
        }

        @Override
        public Object getItem(int i) {
            return null;
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public boolean hasStableIds() {
            return false;
        }

        @Override
        public View getView(int i, View view, ViewGroup viewGroup) {
            int type = getItemViewType(i);
            if (type == ROWTYPE_SHADOW) {
                if (view == null) {
                    view = new ShadowSectionCell(mContext);
                }
                view.setBackgroundResource(i == finalShadowRow? R.drawable.greydivider_bottom : R.drawable.greydivider);
            } else if (type == ROWTYPE_TEXT_SETTINGS) {
                if (view == null) {
                    view = new TextSettingsCell(mContext);
                    view.setBackgroundColor(0xffffffff);
                }
                TextSettingsCell textCell = (TextSettingsCell) view;
                if (i == textSizeRow) {
                    SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
                    int size = preferences.getInt("msg_font_size", defMsgFontSize());
                    textCell.setTextAndValue(mContext.getString(R.string.TextSize), String.format("%d", size), true);
                } else if (i == cacheRow) {
                    textCell.setText(mContext.getString(R.string.CacheSettings), true);
                } 
            } else if (type == ROWTYPE_CHECK) {
                if (view == null) {
                    view = new TextCheckCell(mContext);
                    view.setBackgroundColor(0xffffffff);
                }
                TextCheckCell textCell = (TextCheckCell) view;

                SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
                if (i == sendByEnterRow) {
                    textCell.setTextAndCheck(mContext.getString(R.string.SendByEnter), preferences.getBoolean("send_by_enter", false), true);
                } else if (i == raiseToSpeakRow) {
                    textCell.setTextAndCheck(mContext.getString(R.string.RaiseToSpeak), MediaController.getInstance().canRaiseToSpeak(), false);
                } else if (i == autoplayGifsRow) {
                    textCell.setTextAndCheck(mContext.getString(R.string.AutoplayGifs), MediaController.getInstance().canAutoplayGifs(), true);
                } else if (i == directShareRow) {
                    textCell.setTextAndValueAndCheck(mContext.getString(R.string.DirectShare), mContext.getString(R.string.DirectShareInfo), MediaController.getInstance().canDirectShare(), false, true);
                }
            }
            return view;
        }

        @Override
        public int getItemViewType(int i) {
            if (i == finalShadowRow ) {
                return ROWTYPE_SHADOW;
            } else if ( i == sendByEnterRow || i == raiseToSpeakRow || i == autoplayGifsRow || i == directShareRow) {
                return ROWTYPE_CHECK;
            } else {
                return ROWTYPE_TEXT_SETTINGS;
            }
        }

        @Override
        public int getViewTypeCount() {
            return ROWTYPE_COUNT;
        }

        @Override
        public boolean isEmpty() {
            return false;
        }
    }
}
