// Crop at original resolution; percentages always refer to the original image.
export function cropRectangle(width,height,region={left:0,top:0,right:100,bottom:100}){
 const {left,top,right,bottom}=region;
 if(![left,top,right,bottom].every(Number.isFinite)||left<0||top<0||right>100||bottom>100||right<=left||bottom<=top)throw new Error('読み取る範囲を確認してください。');
 const x=Math.floor(width*left/100),y=Math.floor(height*top/100);
 return {left:x,top:y,width:Math.ceil(width*right/100)-x,height:Math.ceil(height*bottom/100)-y};
}
