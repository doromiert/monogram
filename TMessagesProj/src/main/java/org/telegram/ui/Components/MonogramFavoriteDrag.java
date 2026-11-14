// SPDX-License-Identifier: GPL-3.0-or-later
package org.telegram.ui.Components;
import android.view.*;
import androidx.recyclerview.widget.*;
import org.telegram.ui.ContentPreviewViewer;
/** Favorite drag starts before preview activation; preview browsing always retains the gesture. */
public final class MonogramFavoriteDrag extends RecyclerView.SimpleOnItemTouchListener {
 public interface Model {boolean favorite(int position);void move(int from,int to);void finish();default void started(){}}
 public static final class Hold {
  private long down;private float x,y;private boolean blocked,started;
  public void down(long time,float x,float y){down=time;this.x=x;this.y=y;blocked=started=false;}
  public boolean move(long time,float x,float y,float slop,long previewTimeout,boolean preview){
   if(preview||time-down>=previewTimeout)blocked=true;
   if(started||blocked)return false;
   if(Math.hypot(x-this.x,y-this.y)<=slop)return false;
   if(time-down<Math.min(120,previewTimeout/2)){blocked=true;return false;}
   started=true;return true;
  }
 }
 private final RecyclerListView list;private final Model model;private final ItemTouchHelper helper;private final Hold hold=new Hold();private RecyclerView.ViewHolder pressed;
 public MonogramFavoriteDrag(RecyclerListView list,Model model){this.list=list;this.model=model;
  helper=new ItemTouchHelper(new ItemTouchHelper.Callback(){
   @Override public boolean isLongPressDragEnabled(){return false;}
   @Override public boolean isItemViewSwipeEnabled(){return false;}
   @Override public int getMovementFlags(RecyclerView recycler,RecyclerView.ViewHolder holder){return makeMovementFlags(model.favorite(holder.getAdapterPosition())?ItemTouchHelper.UP|ItemTouchHelper.DOWN|ItemTouchHelper.LEFT|ItemTouchHelper.RIGHT:0,0);}
   @Override public boolean canDropOver(RecyclerView recycler,RecyclerView.ViewHolder current,RecyclerView.ViewHolder target){return model.favorite(target.getAdapterPosition());}
   @Override public boolean onMove(RecyclerView recycler,RecyclerView.ViewHolder from,RecyclerView.ViewHolder to){int a=from.getAdapterPosition(),b=to.getAdapterPosition();if(!model.favorite(a)||!model.favorite(b))return false;model.move(a,b);return true;}
   @Override public void onSwiped(RecyclerView.ViewHolder holder,int direction){}
   @Override public void clearView(RecyclerView recycler,RecyclerView.ViewHolder holder){super.clearView(recycler,holder);model.finish();}
  });helper.attachToRecyclerView(list);list.addOnItemTouchListener(this);
 }
 @Override public boolean onInterceptTouchEvent(RecyclerView recycler,android.view.MotionEvent event){
  if(event.getActionMasked()==MotionEvent.ACTION_DOWN){View child=list.findChildViewUnder(event.getX(),event.getY());pressed=child==null?null:list.getChildViewHolder(child);if(pressed!=null&&!model.favorite(pressed.getAdapterPosition()))pressed=null;hold.down(event.getEventTime(),event.getX(),event.getY());}
  else if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&pressed!=null&&hold.move(event.getEventTime(),event.getX(),event.getY(),ViewConfiguration.get(list.getContext()).getScaledTouchSlop(),ViewConfiguration.getLongPressTimeout(),ContentPreviewViewer.getInstance().isVisible())){
   list.cancelClickRunnables(true);list.stopScroll();ContentPreviewViewer.getInstance().reset();model.started();helper.startDrag(pressed);pressed=null;
  }else if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL)pressed=null;
  return false;
 }
}
