import groovy.transform.CompileStatic;
import org.araqne.logdb.groovy.GroovyParserScript;

/*************************************************************************************************
* 최초 작성일자        :    2017-08-02
* 마지막 수정일자      :    2017-08-03  by 안성민
* File Name           :    binary.groovy
* Main Class Name     :    NamedParser
* Main Method Name    :    public Map<String, Object> parse(Map<String, Object> params)
* 작성자               :    안성민
* 기능 설명            :    바이너리 코드 파싱
**************************************************************************************************/

@CompileStatic
class NamedParser extends GroovyParserScript {

    final int BIT_SIZE            =  2  ;
    final int BYTE_SIZE           =  4  ;
	final int INTEGER_SIZE_LEN    =  8  ;
    final int SHORT_SIZE_LEN      =  4  ;     // for short binary code parsing
	final int FLOAT_SIZE_LEN      =  8  ;     // for float and integer parsing
	final int HEX_SIZE_BUFFER     =  16 ;

	final String RETURN_LINE_NAME = "outputline" ;  // 리턴되는 라인의 이름

	int col_size = 0;            // column의 크기 int Type ex) 1024, 2048
	String column_size = "";     // column의 크기 HEX String ex) 00 00 04 00

	int row_num = 0;             // row index num

	int line_size = 0;           // for past line length
	int line_full_size = 0;      // line column length , fix value
	String temp_past_row = "";   // temp past row String
	String temp_curr_row = "";   // temp current row String
	

/***********************************************************************************
 // todo .
    String pastFilename = "";    // 변수 초기화를 위한 비교값, flag  
	                             // type : Instance Valuable
								 // 추후 디렉토리 와쳐를 완성시키면 위아래 주석 해제      
************************************************************************************/


/**
* 작성자        : 안성민
* method name  : parse()
* return value : Map<String, Object>
* param value  : Map<String, Object>
* 기능설명      : row 단위 파싱을위한 groovy파서 호출시의 메인함수 (로그프레소에서 parse로 호출)
*/
	public Map<String, Object> parse(Map<String, Object> params) {

/***********************************
 // todo . 추후 디렉토리 와쳐 완성시 위와 아래의 주석을 제거하고, 아래의 if문을 주석처리하기 바람
            String currFilename = params.get("FILENAME").toString();   // 변수 초기화를 위한 비교값, flag
			if(!pastFilename.equals(currFilename)){                    // 조건문 변경      
************************************/
		if(params.get("_id") == 125){ // 변수 초기화 블럭 . 임시로 사용된 조건문 디렉토리 와쳐에서는 위와같이 변경

			column_size = toBigEndian(params.get("line").toString().substring(0,FLOAT_SIZE_LEN));
			col_size = Integer.parseInt(column_size, HEX_SIZE_BUFFER);
			row_num = 0;
			line_full_size = params.get("line").toString().length();   // 라인컬럼의 문자열 길이
			temp_past_row = params.get("line").toString().substring(column_size.length(), line_full_size);
			line_size = temp_past_row.length();
		}

/***********************************
 // todo . 추후 디렉토리 와쳐 완성시 위와 아래의 주석을 제거
        pastFilename = currFilename;     // 입력된 라인으로 이전라인을 교체
************************************/

        String parse_row = "";       // 최종 parsing 대상 row;
		StringBuilder outputVal = new StringBuilder();
		

		if(row_num == 0 ){ // 컬럼명 가져오기 ( 마지막 8글자 포함하여 )
			if( temp_past_row.length() < (col_size *BYTE_SIZE *BIT_SIZE)){
				StringBuilder tempVal = new StringBuilder();
				temp_curr_row = temp_curr_row.length() > 0 ? params.get("line").toString() : temp_curr_row ; 
				temp_past_row = tempVal.append(temp_past_row)
				                       .append(temp_curr_row).toString();
				temp_curr_row = "For Not null! ^o^ ";
				return null;
			}else{
				StringBuilder tempVal = new StringBuilder();
				temp_curr_row = params.get("line").toString();
				temp_past_row = tempVal.append(temp_past_row)
				                       .append(temp_curr_row).toString();
				parse_row     = temp_past_row.substring(0,  (col_size *FLOAT_SIZE_LEN) + INTEGER_SIZE_LEN );
				temp_past_row = temp_past_row.substring(    (col_size *FLOAT_SIZE_LEN) + INTEGER_SIZE_LEN );
				row_num++;
				params.put(RETURN_LINE_NAME, strToFloatCode(parse_row));
				return params;
			}
		} else{ // short 형태의 문자열 변환 2글자씩 읽는다
			StringBuilder tempVal = new StringBuilder();
			String temp_local = "";
		    temp_curr_row     = params.get("line").toString();
			temp_local        = tempVal.append(temp_past_row)
			                           .append(temp_curr_row).toString();
			if(temp_local.length() <= (col_size *SHORT_SIZE_LEN)+ INTEGER_SIZE_LEN ){
				temp_past_row = temp_local;
				return null;
			}else{
				parse_row     = temp_local.substring(0, (col_size *SHORT_SIZE_LEN ) + INTEGER_SIZE_LEN  );
				temp_past_row = temp_local.substring(   (col_size *SHORT_SIZE_LEN ) + INTEGER_SIZE_LEN  );
				params.put(RETURN_LINE_NAME, strToShortCode(parse_row));
		        return params;
			}
		}
	}

/**
* 작성자        : 안성민
* method name  : strToShortCode()
* return value : String
* param value  : String
* 기능 설명     : HEX code 형태의 문자열을 short형태로 파싱 변환
*/
    private String strToShortCode(String paramStr){
		int paramLen = paramStr.length() - INTEGER_SIZE_LEN;
		int idx = 0;
		int[] returnVal = new int[(paramLen/SHORT_SIZE_LEN)];
     	for(int i = 0; i <= (paramLen-SHORT_SIZE_LEN ); i = i+SHORT_SIZE_LEN){
		 	short temp = (short) Integer.parseInt(toBigEndian( paramStr.substring(i,i+SHORT_SIZE_LEN) ), HEX_SIZE_BUFFER );
			returnVal[idx] = (int)( temp & 0xFFFF );
		 	idx++;
		}
		// todo . 문자열로 리턴하지만 필요시 배열 타입으로 리턴 하는 함수를 새로이 만들어야 한다.
		return Arrays.toString(returnVal);
	}

/**
* 작성자        : 안성민
* method name  : strToFloatCode()
* return value : String
* param value  : String
* 기능 설명     : HEX code 형태의 문자열을 float형태로 파싱 변환
*/
    private String strToFloatCode(String paramStr){
		int paramLen = paramStr.length();
		int idx = 0;
		float[] returnVal = new float[(paramLen/FLOAT_SIZE_LEN) -1];
		for(int i = 0; i< (paramLen-FLOAT_SIZE_LEN); i = i+FLOAT_SIZE_LEN){
		 	int temp =  Integer.parseInt(toBigEndian( paramStr.substring(i,i+FLOAT_SIZE_LEN) ), HEX_SIZE_BUFFER );
			returnVal[idx] = Float.intBitsToFloat(temp.intValue());
		 	idx++;
		}
		// todo . 문자열로 리턴하지만 필요시 배열 타입으로 리턴 하는 함수를 새로이 만들어야 한다.
		return Arrays.toString(returnVal);
	}

/**
* @deprecated
* 작성자        : 안성민
* method name  : mergeString()
* return value : String
* param value  : null
* 기능 설명     : 문자열의 필요없는 부분을 제거하고 이전 row를 필요한 크기만큼 잘라내어 병합
*/
    private String mergeString(){
		StringBuilder outputVal = new StringBuilder();
		if(temp_curr_row.length() >= 0){
			String splitString = temp_curr_row.substring(0, line_full_size-line_size);
			outputVal.append(temp_past_row).append(splitString);
			temp_past_row = outputVal.toString();
			temp_curr_row = splitString;
			return ;
		}
		return ;
	}

/**
* 작성자        : 안성민
* method name  : toBigEndian()
* return value : String
* param value  : String
* 기능 설명     : Little-Endian 형식의 코드를 Big-Endian 형식으로 변환
*/
	private String toBigEndian(String biCode){
		int codeLen = biCode.length(); // 문자열 길이
		StringBuilder returnVal = new StringBuilder();
		for(int i = 0 ; i < codeLen; i = i+2){
			returnVal.append(biCode.substring(codeLen-i-2, codeLen-i));
		}
		return returnVal.toString();
	}
}


/**************************************************************************************************

Created by . Ahn Seong Min
email . asdhgfasd@naver.com

***************************************************************************************************/
